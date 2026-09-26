/*
 * Hook library loaded into the Unreal Tournament process. It steps the engine clocks by a fixed amount per presented
 * frame, and sends every frame presented while the demo plays to the frame socket as raw BGRA pixels.
 */

#ifdef _WIN32
#include <winsock2.h>
#include <windows.h>
#else
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <dlfcn.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <signal.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>
#endif

/* Reads the processor's time stamp counter. */
#ifdef _MSC_VER
#include <intrin.h>
#define readCycles() __rdtsc()
#else
#define readCycles() __builtin_ia32_rdtsc()
#endif

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define GL_VIEWPORT 0x0BA2
#define GL_BGRA 0x80E1
#define GL_UNSIGNED_BYTE 0x1401

/* Connection attempts to the frame socket, and the delay between two of them. */
#define CONNECT_ATTEMPTS 200
#define CONNECT_DELAY_MS 50

/* Exported native registration of UDemoInterface.execSetSpeed in udemo: a slot holding the native function. */
#define SYMBOL_SET_SPEED "intUDemoInterfaceexecSetSpeed"

/* FTime ticks in one second. */
#define CLOCK_UNIT 4294967296LL

/* Highest frame rate UTRECORDER_FPS accepts. */
#define CLOCK_MAX_RATE 1000

/* Clock steps in one frame. Before the demo plays, a presented frame advances the clocks by one step instead. */
#define CLOCK_PREROLL_STEPS 20

/* Calling convention of the OpenGL entry points. */
#ifdef _WIN32
#define GL_ENTRY WINAPI
#else
#define GL_ENTRY
#endif

/* SDL_GL_GetProcAddress, through which the Linux port resolves OpenGL entry points. */
typedef void *(*GetProcAddressFunction)(const char *);
typedef void(GL_ENTRY *GetIntegervFunction)(unsigned int, int *);
typedef void(GL_ENTRY *ReadPixelsFunction)(int, int, int, int, unsigned int, unsigned int, void *);

/* glGetIntegerv and glReadPixels, resolved on the first captured frame. */
static GetIntegervFunction readViewport;
static ReadPixelsFunction readPixels;

/* The frame read last, and the frame read before it that waits to be sent, each pixelWidth by pixelHeight BGRA. */
static unsigned char *pixels;
static unsigned char *pending;
static size_t pendingSize;
static int pixelWidth;
static int pixelHeight;

/* Set once capture cannot go on. No frame is read or sent after that. */
static int captureDisabled;

/* The connection to the frame socket. */
#ifdef _WIN32
static SOCKET stream = INVALID_SOCKET;
#define STREAM_CLOSED INVALID_SOCKET
#define closeStream(handle) closesocket(handle)
#define sleepMillis(millis) Sleep(millis)
#else
static int stream = -1;
#define STREAM_CLOSED (-1)
#define closeStream(handle) close(handle)
#define sleepMillis(millis) usleep((millis) * 1000)
#endif

/* Returns the environment variable name as a number from 1 to maximum, or 0 when it is unset or out of that range. */
static int environmentNumber(const char *name, int maximum) {
    const char *value = getenv(name);
    int number;
    if (!value) return 0;
    number = atoi(value);
    if (number <= 0 || number > maximum) return 0;
    return number;
}

/* Starts the socket library. Returns whether it started. */
static int startSockets(void) {
#ifdef _WIN32
    WSADATA startup;
    return WSAStartup(MAKEWORD(2, 2), &startup) == 0;
#else
    return 1;
#endif
}

/* Connects stream to the frame socket on the loopback port in UTRECORDER_PORT. Disables capture when that fails. */
static void openStream(void) {
    struct sockaddr_in address;
    int attempt;
    int port = environmentNumber("UTRECORDER_PORT", 65535);
    int nodelay = 1;
    if (!port || !startSockets()) {
        captureDisabled = 1;
        return;
    }
    memset(&address, 0, sizeof(address));
    address.sin_family = AF_INET;
    address.sin_port = htons((unsigned short) port);
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    for (attempt = 0; attempt < CONNECT_ATTEMPTS; attempt++) {
        stream = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
        if (stream == STREAM_CLOSED) break;
        if (connect(stream, (struct sockaddr *) &address, sizeof(address)) == 0) {
            setsockopt(stream, IPPROTO_TCP, TCP_NODELAY, (const char *) &nodelay, sizeof(nodelay));
            return;
        }
        closeStream(stream);
        stream = STREAM_CLOSED;
        sleepMillis(CONNECT_DELAY_MS);
    }
    captureDisabled = 1;
}

/* Sends size bytes of data to stream. Returns whether every byte was sent. */
static int sendAll(const unsigned char *data, size_t size) {
    while (size > 0) {
        int chunk = size > 0x100000 ? 0x100000 : (int) size;
        int written = send(stream, (const char *) data, chunk, 0);
        if (written <= 0) return 0;
        data += written;
        size -= (size_t) written;
    }
    return 1;
}

/* Resolves readViewport and readPixels. Disables capture when either is missing. */
static void resolveGl(void) {
#ifdef _WIN32
    HMODULE library = GetModuleHandleA("opengl32.dll");
    if (!library) library = LoadLibraryA("opengl32.dll");
    if (!library) {
        captureDisabled = 1;
        return;
    }
    readViewport = (GetIntegervFunction) GetProcAddress(library, "glGetIntegerv");
    readPixels = (ReadPixelsFunction) GetProcAddress(library, "glReadPixels");
#else
    GetProcAddressFunction lookup = (GetProcAddressFunction) dlsym(RTLD_NEXT, "SDL_GL_GetProcAddress");
    if (!lookup) {
        captureDisabled = 1;
        return;
    }
    readViewport = (GetIntegervFunction) lookup("glGetIntegerv");
    readPixels = (ReadPixelsFunction) lookup("glReadPixels");
#endif
    if (!readViewport || !readPixels) captureDisabled = 1;
}

/* Sends the pending frame, opening stream first if needed. Closes stream and disables capture when the send fails. */
static void sendPending(void) {
    if (!pendingSize) return;
    if (stream == STREAM_CLOSED) openStream();
    if (captureDisabled) return;
    if (!sendAll(pending, pendingSize)) {
        closeStream(stream);
        stream = STREAM_CLOSED;
        captureDisabled = 1;
    }
}

/* Reallocates both frame buffers for a width by height viewport and drops the pending frame. */
static void resizeFrames(int width, int height) {
    size_t size = (size_t) width * height * 4;
    free(pixels);
    free(pending);
    pixelWidth = width;
    pixelHeight = height;
    pendingSize = 0;
    pixels = malloc(size);
    pending = malloc(size);
    if (!pixels || !pending) captureDisabled = 1;
}

/* Reads the current viewport into pixels, sends the pending frame, then makes the frame just read the pending one. */
static void captureFrame(void) {
    int viewport[4];
    size_t size;
    unsigned char *sent;
    readViewport(GL_VIEWPORT, viewport);
    if (viewport[2] <= 0 || viewport[3] <= 0) return;
    if (viewport[2] != pixelWidth || viewport[3] != pixelHeight) resizeFrames(viewport[2], viewport[3]);
    if (captureDisabled) return;
    size = (size_t) pixelWidth * pixelHeight * 4;
    readPixels(0, 0, pixelWidth, pixelHeight, GL_BGRA, GL_UNSIGNED_BYTE, pixels);
    sendPending();
    if (captureDisabled) return;
    sent = pending;
    pending = pixels;
    pixels = sent;
    pendingSize = size;
}

/* The values each patched clock function returned when it was patched. The hooks count on from them. */
static double clockBaseNew;
static double clockBaseNoCheck;
static double clockBaseCompat;
static long long clockBaseSeconds;
static long long clockBaseSlow;

/* The frame rate in UTRECORDER_FPS, and the clock steps taken so far. */
static long long clockRate;
static long long clockFrame;

/* Clock state: installation tried, clocks patched and stepping, demo started, demo ended. */
static int clockInstalled;
static int clockRunning;
static int clockArmed;
static int clockFinished;

/* GSecondsPerCycle and GSecondsPerCycleLong in Core. */
static float *clockCycleScale;
static double *clockCycleScaleLong;

/* The cycle counter at the previous scaleCycles call, and the cycle scale that scaleCycles sets before the demo. */
static unsigned long long clockPreviousCycles;
static double clockPreroll;

/* Whether the frame delta sites are patched to read a virtual count, and whether they read it now. */
static int clockCyclesVirtual;
static unsigned char clockCyclesArmed;

/* Presented frames since the hooks loaded, and frames counted since capture was armed. */
static long long presentCount;
static long long demoFrames;

/* GIsDemoPlayback in Core, and whether its lookup was tried. */
static int *demoFlag;
static int demoResolved;

/* Whether execSetSpeed was called, which arms the capture, and whether its slot is hooked. */
static int captureArmed;
static int speedHooked;

/* Returns the clock steps in one second. */
static long long clockSteps(void) {
    return clockRate * CLOCK_PREROLL_STEPS;
}

/* Returns the seconds the clocks have stepped since they were patched. */
static double clockElapsed(void) {
    return (double) clockFrame / (double) clockSteps();
}

/* Returns the FTime ticks the clocks have stepped since they were patched. */
static long long clockTicks(void) {
    return (clockFrame * CLOCK_UNIT) / clockSteps();
}

#ifdef _WIN32

#define SYMBOL_SECONDS "?appSeconds@@YA?AVFTime@@XZ"
#define SYMBOL_SLOW "?appSecondsSlow@@YA?AVFTime@@XZ"
#define SYMBOL_NEW "?appSecondsNew@@YANXZ"
#define SYMBOL_NOCHECK "?appSecondsNewNoCheck@@YANXZ"
#define SYMBOL_COMPAT "?appSecondsCompat@@YANXZ"
#define SYMBOL_CYCLE "?GSecondsPerCycle@@3MA"
#define SYMBOL_CYCLE_LONG "?GSecondsPerCycleLong@@3NA"
#define SYMBOL_DEMO "?GIsDemoPlayback@@3HA"
#define SET_SPEED_ABI __fastcall
#define SET_SPEED_ARGS void *self, void *held, void *stack, void *result
#define SET_SPEED_PASS self, held, stack, result

/* Whether the engine reads the cycle counter at inlined frame delta sites, which patchCycles can patch. */
#define CLOCK_INLINED 1

typedef long long *(__cdecl *TimeFunction)(long long *);
typedef double(__cdecl *SecondsFunction)(void);

/* GSecondsPerCycleLong as it was before the clock was installed, and the virtual cycles in one clock step. */
static double clockRealScale;
static double clockCyclesPerStep;

/* The virtual count the patched sites read while the demo plays, and its value at clock step zero. */
static unsigned long long clockCycleCount;
static unsigned long long clockCycleBase;

/* The amount added to the real counter after the patched sites are released. */
static unsigned long long clockCycleOffset;

/* The appSeconds hook. */
static long long *__cdecl hookSeconds(long long *result) {
    *result = clockBaseSeconds + clockTicks();
    return result;
}

/* The appSecondsSlow hook. */
static long long *__cdecl hookSlow(long long *result) {
    *result = clockBaseSlow + clockTicks();
    return result;
}

/* Returns the FTime ticks that original returns. */
static long long readTime(TimeFunction original) {
    long long value = 0;
    original(&value);
    return value;
}

/* Returns the address of the export name in Core, or 0 when Core or the export is missing. */
static void *coreSymbol(const char *name) {
    HMODULE core = GetModuleHandleA("Core.dll");
    if (!core) return 0;
    return (void *) GetProcAddress(core, name);
}

/* Returns the address of the export name in module, or 0 when module is 0 or the export is missing. */
static void *moduleSymbol(void *module, const char *name) {
    if (!module) return 0;
    return (void *) GetProcAddress((HMODULE) module, name);
}

/* Writes value to the pointer at slot, making slot writable first. Returns whether the write happened. */
static int writePointer(void **slot, void *value) {
    DWORD protection;
    if (!VirtualProtect(slot, sizeof(value), PAGE_READWRITE, &protection)) return 0;
    *slot = value;
    VirtualProtect(slot, sizeof(value), protection, &protection);
    return 1;
}

/* Writes size bytes of code to target, making target writable first. Returns whether the write happened. */
static int writeCode(void *target, const unsigned char *code, size_t size) {
    DWORD protection;
    if (!VirtualProtect(target, size, PAGE_EXECUTE_READWRITE, &protection)) return 0;
    memcpy(target, code, size);
    VirtualProtect(target, size, protection, &protection);
    FlushInstructionCache(GetCurrentProcess(), target, size);
    return 1;
}

/* The frame delta helper inlined into UGameEngine::Tick: rdtsc; mov esi,eax; mov edi,edx; mov ecx,esi. */
static const unsigned char CYCLE_SITE_ENGINE[] = {0x9B, 0x0F, 0x31, 0x8B, 0xF0, 0x8B, 0xFA, 0x8B, 0xCE};

/* The same helper inlined into the launcher's main loop: rdtsc; mov esi,eax; mov eax,edx; mov [ebp-1Ch],eax. */
static const unsigned char CYCLE_SITE_LAUNCHER[] = {0x9B, 0x0F, 0x31, 0x8B, 0xF0, 0x8B, 0xC2, 0x89, 0x45, 0xE4, 0x8B, 0xCE};

/* Bytes a patched site's call replaces, and the size of the thunk it calls. */
#define CYCLE_SITE_BYTES 6
#define CYCLE_THUNK_BYTES 12

/*
 * Returns the last match of pattern in size bytes at start, or found when there is none. Sets duplicate when a match
 * follows an earlier one, including the found passed in.
 */
static unsigned char *findInSection(unsigned char *start, size_t size, const unsigned char *pattern, size_t length,
                                    unsigned char *found, int *duplicate) {
    size_t at;
    for (at = 0; at + length <= size; at++) {
        if (memcmp(start + at, pattern, length) != 0) continue;
        if (found) *duplicate = 1;
        found = start + at;
    }
    return found;
}

/* Returns the only match of pattern in the code sections of module, or 0 when there is none or more than one. */
static unsigned char *findUnique(HMODULE module, const unsigned char *pattern, size_t length) {
    IMAGE_DOS_HEADER *dos = (IMAGE_DOS_HEADER *) module;
    IMAGE_NT_HEADERS *headers;
    IMAGE_SECTION_HEADER *section;
    unsigned char *found = 0;
    int duplicate = 0;
    WORD index;
    if (!module) return 0;
    headers = (IMAGE_NT_HEADERS *) ((char *) module + dos->e_lfanew);
    section = IMAGE_FIRST_SECTION(headers);
    for (index = 0; index < headers->FileHeader.NumberOfSections; index++, section++) {
        if (!(section->Characteristics & IMAGE_SCN_CNT_CODE)) continue;
        found = findInSection((unsigned char *) module + section->VirtualAddress, section->Misc.VirtualSize, pattern,
                              length, found, &duplicate);
    }
    return duplicate ? 0 : found;
}

/* Hands every site back to the real counter, shifted to carry on from the virtual count instead of waiting for it. */
static void releaseCycles(void) {
    unsigned long long now;
    if (!clockCyclesArmed) return;
    now = readCycles();
    if (clockCycleCount > now) clockCycleOffset = clockCycleCount - now;
    clockCyclesArmed = 0;
}

/*
 * Returns the count every patched site reads in place of rdtsc: the virtual count while the clock is armed and the demo
 * plays, the shifted real counter otherwise. Releases the sites on the first read after the demo stops.
 */
static unsigned long long __cdecl readSiteCycles(void) {
    if (clockCyclesArmed && !*demoFlag) releaseCycles();
    if (clockCyclesArmed) return clockCycleCount;
    return readCycles() + clockCycleOffset;
}

/*
 * Returns a new thunk that replaces a site's rdtsc and its two register moves, or 0 when it cannot be allocated. The
 * thunk runs push ecx; call readSiteCycles; pop ecx; the site's moves; ret.
 */
static unsigned char *buildCycleThunk(unsigned char *moves) {
    unsigned char *thunk = (unsigned char *) VirtualAlloc(0, CYCLE_THUNK_BYTES, MEM_COMMIT | MEM_RESERVE,
                                                          PAGE_EXECUTE_READWRITE);
    int offset;
    if (!thunk) return 0;
    offset = (int) ((char *) (void *) readSiteCycles - (char *) (thunk + 6));
    thunk[0] = 0x51;
    thunk[1] = 0xE8;
    memcpy(thunk + 2, &offset, 4);
    thunk[6] = 0x59;
    memcpy(thunk + 7, moves, 4);
    thunk[11] = 0xC3;
    return thunk;
}

/* Replaces the rdtsc and register moves of the site at site with a call to a new thunk. Returns whether it did. */
static int patchCycleSite(unsigned char *site) {
    unsigned char *rdtsc = site + 1;
    unsigned char *thunk = buildCycleThunk(rdtsc + 2);
    unsigned char call[CYCLE_SITE_BYTES];
    int offset;
    if (!thunk) return 0;
    offset = (int) ((char *) thunk - (char *) rdtsc - 5);
    call[0] = 0xE8;
    memcpy(call + 1, &offset, 4);
    call[5] = 0x90;
    return writeCode(rdtsc, call, CYCLE_SITE_BYTES);
}

/*
 * Patches the frame delta sites in Engine and the launcher to read the virtual count, and resolves demoFlag. Returns
 * whether both sites were patched.
 */
static int patchCycles(void) {
    unsigned char *engine = findUnique(GetModuleHandleA("Engine.dll"), CYCLE_SITE_ENGINE, sizeof(CYCLE_SITE_ENGINE));
    unsigned char *launcher = findUnique(GetModuleHandleA(0), CYCLE_SITE_LAUNCHER, sizeof(CYCLE_SITE_LAUNCHER));
    demoResolved = 1;
    demoFlag = (int *) coreSymbol(SYMBOL_DEMO);
    if (!engine || !launcher || !demoFlag) return 0;
    clockRealScale = *clockCycleScaleLong;
    clockCyclesPerStep = 1.0 / (clockRealScale * (double) clockSteps());
    return patchCycleSite(engine) && patchCycleSite(launcher);
}

/* Starts the virtual count at the real counter and restores the real cycle scale, when the sites are patched. */
static void armCycles(void) {
    unsigned long long now;
    if (!clockCyclesVirtual) return;
    now = readCycles();
    clockCycleBase = now - (unsigned long long) ((double) clockFrame * clockCyclesPerStep);
    clockCycleCount = now;
    *clockCycleScaleLong = clockRealScale;
    if (clockCycleScale) *clockCycleScale = (float) clockRealScale;
    clockCyclesArmed = 1;
}

/* Moves the virtual count to the current clock step, while the sites read it. */
static void stepCycles(void) {
    if (!clockCyclesArmed) return;
    clockCycleCount = clockCycleBase + (unsigned long long) ((double) clockFrame * clockCyclesPerStep);
}

#else

#define SYMBOL_SECONDS "_Z10appSecondsv"
#define SYMBOL_SLOW "_Z14appSecondsSlowv"
#define SYMBOL_NEW "_Z13appSecondsNewv"
#define SYMBOL_NOCHECK "_Z20appSecondsNewNoCheckv"
#define SYMBOL_COMPAT "_Z16appSecondsCompatv"
#define SYMBOL_CYCLE "GSecondsPerCycle"
#define SYMBOL_CYCLE_LONG "GSecondsPerCycleLong"
#define SYMBOL_DEMO "GIsDemoPlayback"
#define SET_SPEED_ABI
#define SET_SPEED_ARGS void *self, void *stack, void *result
#define SET_SPEED_PASS self, stack, result

/* Whether the engine reads the cycle counter at inlined frame delta sites, which patchCycles can patch. */
#define CLOCK_INLINED 0

/* FTime as i386 System V returns it: through a hidden result pointer the callee pops with ret 4. */
typedef struct {
    long long value;
} Time;

typedef Time (*TimeFunction)(void);
typedef double (*SecondsFunction)(void);

/* The Core library, opened on the first export not found in the global scope. */
static void *coreLibrary;

/* The appSeconds hook. */
static Time hookSeconds(void) {
    Time time;
    time.value = clockBaseSeconds + clockTicks();
    return time;
}

/* The appSecondsSlow hook. */
static Time hookSlow(void) {
    Time time;
    time.value = clockBaseSlow + clockTicks();
    return time;
}

/* Returns the FTime ticks that original returns. */
static long long readTime(TimeFunction original) {
    return original().value;
}

/* Returns the address of the export name in Core, or 0 when Core or the export is missing. */
static void *coreSymbol(const char *name) {
    void *symbol = dlsym(RTLD_DEFAULT, name);
    if (symbol) return symbol;
    if (!coreLibrary) coreLibrary = dlopen("Core.so", RTLD_NOLOAD | RTLD_LAZY);
    if (!coreLibrary) coreLibrary = dlopen("libCore.so", RTLD_NOLOAD | RTLD_LAZY);
    if (!coreLibrary) return 0;
    return dlsym(coreLibrary, name);
}

/* Returns the address of the export name in module, or 0 when module is 0 or the export is missing. */
static void *moduleSymbol(void *module, const char *name) {
    if (!module) return 0;
    return dlsym(module, name);
}

/* Writes value to the pointer at slot, making slot writable first. Returns whether the write happened. */
static int writePointer(void **slot, void *value) {
    long granularity = sysconf(_SC_PAGESIZE);
    char *page = (char *) ((uintptr_t) slot & ~(uintptr_t) (granularity - 1));
    size_t span = (size_t) ((char *) slot + sizeof(value) - page);
    if (mprotect(page, span, PROT_READ | PROT_WRITE) != 0) return 0;
    *slot = value;
    return 1;
}

/* Writes size bytes of code to target, making target writable first. Returns whether the write happened. */
static int writeCode(void *target, const unsigned char *code, size_t size) {
    long granularity = sysconf(_SC_PAGESIZE);
    char *page = (char *) ((uintptr_t) target & ~(uintptr_t) (granularity - 1));
    size_t span = (size_t) ((char *) target + size - page);
    if (mprotect(page, span, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) return 0;
    memcpy(target, code, size);
    __builtin___clear_cache((char *) target, (char *) target + size);
    mprotect(page, span, PROT_READ | PROT_EXEC);
    return 1;
}

/* Patches no frame delta site, since none is inlined. Returns 0. */
static int patchCycles(void) {
    return 0;
}

/* Does nothing, since no frame delta site is patched. */
static void armCycles(void) {
}

/* Does nothing, since no frame delta site is patched. */
static void stepCycles(void) {
}

/* Does nothing, since no frame delta site is patched. */
static void releaseCycles(void) {
}

#endif

/* The appSecondsNew hook. */
static double hookNew(void) {
    return clockBaseNew + clockElapsed();
}

/* The appSecondsNewNoCheck hook. */
static double hookNoCheck(void) {
    return clockBaseNoCheck + clockElapsed();
}

/* The appSecondsCompat hook. */
static double hookCompat(void) {
    return clockBaseCompat + clockElapsed();
}

/* Overwrites the start of target with a jump to replacement. Returns whether the write happened. */
static int installJump(void *target, void *replacement) {
    unsigned char code[5];
    int offset = (int) ((char *) replacement - (char *) target - 5);
    code[0] = 0xE9;
    memcpy(code + 1, &offset, sizeof(offset));
    return writeCode(target, code, sizeof(code));
}

/* Stores the FTime the Core function name returns in base, then redirects the function to replacement. */
static int patchTime(const char *name, long long *base, void *replacement) {
    TimeFunction original = (TimeFunction) coreSymbol(name);
    if (!original) return 0;
    *base = readTime(original);
    return installJump((void *) original, replacement);
}

/* Stores the seconds the Core function name returns in base, then redirects the function to replacement. */
static int patchSeconds(const char *name, double *base, void *replacement) {
    SecondsFunction original = (SecondsFunction) coreSymbol(name);
    if (!original) return 0;
    *base = original();
    return installJump((void *) original, replacement);
}

/* Sets the Core flags GTimestampPreferred and GTGTPreferred to 0, where they exist. */
static void clearTimestampGates(void) {
    static const char *const gates[] = {"GTimestampPreferred", "GTGTPreferred", 0};
    const char *const *gate;
    int *flag;
    for (gate = gates; *gate; gate++) {
        flag = (int *) coreSymbol(*gate);
        if (flag) *flag = 0;
    }
}

/*
 * Sets the cycle scale so the real cycles between two presented frames count as one frame while the demo plays, and as
 * clockPreroll before it. Does nothing while the sites read the virtual count, after the demo, or without inlined sites.
 */
static void scaleCycles(void) {
    unsigned long long now;
    unsigned long long span;
    if (!CLOCK_INLINED || !clockCycleScaleLong || clockCyclesArmed || clockFinished) return;
    now = readCycles();
    if (clockPreviousCycles) {
        span = now - clockPreviousCycles;
        if (!clockArmed) {
            *clockCycleScaleLong = clockPreroll;
        } else if (span > 0) {
            *clockCycleScaleLong = 1.0 / ((double) clockRate * (double) span);
        }
        if (clockCycleScale) *clockCycleScale = (float) *clockCycleScaleLong;
    }
    clockPreviousCycles = now;
}

/* Returns whether GIsDemoPlayback is set. Resolves it on the first call and reports when it is missing. */
static int demoPlaying(void) {
    if (!demoResolved) {
        demoResolved = 1;
        demoFlag = (int *) coreSymbol(SYMBOL_DEMO);
        if (!demoFlag) {
            fprintf(stderr, "uthooks: %s not found, nothing will be captured\n", SYMBOL_DEMO);
            fflush(stderr);
        }
    }
    return demoFlag && *demoFlag;
}

/*
 * Ends the capture: releases the cycle sites to the real counter when they have not released themselves already, drops
 * the pending frame, closes stream and reports the frame count.
 */
static void finishCapture(void) {
    clockFinished = 1;
    releaseCycles();
    pendingSize = 0;
    if (stream != STREAM_CLOSED) {
        closeStream(stream);
        stream = STREAM_CLOSED;
    }
    captureDisabled = 1;
    fprintf(stderr, "uthooks: demo ended after %lld frames, kept %lld\n", demoFrames,
            demoFrames > 2 ? demoFrames - 2 : 0);
    fflush(stderr);
}

/* Arms the clock when the demo starts, and finishes the capture when it stops. */
static void trackDemo(void) {
    if (clockFinished) return;
    if (!clockArmed) {
        if (!demoPlaying()) return;
        clockArmed = 1;
        armCycles();
        fprintf(stderr, "uthooks: demo started at frame %lld\n", presentCount);
        fflush(stderr);
        return;
    }
    if (!demoPlaying()) finishCapture();
}

typedef void(SET_SPEED_ABI *SetSpeedFunction)(SET_SPEED_ARGS);

/* The native execSetSpeed that hookSetSpeed calls on. */
static SetSpeedFunction realSetSpeed;

/* The execSetSpeed hook. Arms the capture on the first call before the demo ends. */
static void SET_SPEED_ABI hookSetSpeed(SET_SPEED_ARGS) {
    if (!captureArmed && !clockFinished) {
        captureArmed = 1;
        fprintf(stderr, "uthooks: capture armed at frame %lld\n", presentCount);
        fflush(stderr);
    }
    if (realSetSpeed) realSetSpeed(SET_SPEED_PASS);
}

/* Points the execSetSpeed slot of module at hookSetSpeed, when module exports it and it is not hooked yet. */
static void installSpeedHook(void *module) {
    void **slot;
    if (speedHooked || !module) return;
    slot = (void **) moduleSymbol(module, SYMBOL_SET_SPEED);
    if (!slot || !*slot) return;
    realSetSpeed = (SetSpeedFunction) *slot;
    if (!writePointer(slot, (void *) hookSetSpeed)) {
        realSetSpeed = 0;
        fprintf(stderr, "uthooks: %s is not writable, capture cannot be armed\n", SYMBOL_SET_SPEED);
        fflush(stderr);
        return;
    }
    speedHooked = 1;
}

/* Returns whether a required clock function is missing from Core. Reports the first one missing. */
static int missingClock(void) {
    static const char *const required[] = {SYMBOL_NEW, SYMBOL_NOCHECK, SYMBOL_SECONDS, SYMBOL_SLOW, 0};
    const char *const *name;
    for (name = required; *name; name++) {
        if (!coreSymbol(*name)) {
            fprintf(stderr, "uthooks: %s not found, playback will not be frame locked\n", *name);
            return 1;
        }
    }
    return 0;
}

/*
 * Patches the Core clock functions and the frame delta sites to step at the frame rate in UTRECORDER_FPS. Leaves the
 * clocks alone when the rate is unset or a required clock function is missing.
 */
static void installClock(void) {
    int patched = 0;
    int expected = 4;
    clockInstalled = 1;
    clockRate = environmentNumber("UTRECORDER_FPS", CLOCK_MAX_RATE);
    if (!clockRate) return;
    if (missingClock()) return;
    patched += patchSeconds(SYMBOL_NEW, &clockBaseNew, (void *) hookNew);
    patched += patchSeconds(SYMBOL_NOCHECK, &clockBaseNoCheck, (void *) hookNoCheck);
    patched += patchTime(SYMBOL_SECONDS, &clockBaseSeconds, (void *) hookSeconds);
    patched += patchTime(SYMBOL_SLOW, &clockBaseSlow, (void *) hookSlow);
    if (coreSymbol(SYMBOL_COMPAT)) {
        expected++;
        patched += patchSeconds(SYMBOL_COMPAT, &clockBaseCompat, (void *) hookCompat);
    }
    clearTimestampGates();
    clockCycleScale = (float *) coreSymbol(SYMBOL_CYCLE);
    clockCycleScaleLong = (double *) coreSymbol(SYMBOL_CYCLE_LONG);
    if (CLOCK_INLINED && clockCycleScaleLong && *clockCycleScaleLong > 0.0) {
        clockPreroll = *clockCycleScaleLong / CLOCK_PREROLL_STEPS;
        clockCyclesVirtual = patchCycles();
    }
    if (CLOCK_INLINED && !clockCyclesVirtual) {
        fprintf(stderr, "uthooks: frame delta sites not found, scaling real cycles instead\n");
    }
    scaleCycles();
    clockRunning = 1;
    if (patched < expected) {
        fprintf(stderr, "uthooks: patched %d of %d engine clocks, playback may drift\n", patched, expected);
    }
    fprintf(stderr, "uthooks: engine clock stepping %lld frames per second\n", clockRate);
}

/* Steps the clocks by one frame while the demo plays, and by one step before it. */
static void advanceClock(void) {
    if (!clockRunning) return;
    clockFrame += clockArmed ? CLOCK_PREROLL_STEPS : 1;
    stepCycles();
    scaleCycles();
}

/* Counts a frame of the armed capture, and captures it from the second frame on while capture is enabled. */
static void captureDemoFrame(void) {
    demoFrames++;
    if (demoFrames <= 1 || captureDisabled) return;
    if (!readPixels) resolveGl();
    if (!captureDisabled) captureFrame();
}

/* Runs before every presented frame: installs the clock, tracks the demo and captures the frame. */
static void presentFrame(void) {
    if (!clockInstalled) installClock();
    presentCount++;
    trackDemo();
    if (captureArmed && !clockFinished) captureDemoFrame();
    advanceClock();
}

#ifdef _WIN32

typedef BOOL(WINAPI *SwapBuffersFunction)(HDC);
typedef HMODULE(WINAPI *LoadLibraryWFunction)(LPCWSTR);
typedef HMODULE(WINAPI *LoadLibraryExWFunction)(LPCWSTR, HANDLE, DWORD);

/* The functions the hooks call on. */
static SwapBuffersFunction realSwapBuffers;
static LoadLibraryWFunction realLoadLibraryW;
static LoadLibraryExWFunction realLoadLibraryExW;

/* This library's own module. */
static HMODULE self;

/*
 * Points the import address entry of symbol in the imports that descriptor describes at replacement. Returns whether
 * symbol was among those imports.
 */
static int patchImportEntry(HMODULE module, IMAGE_IMPORT_DESCRIPTOR *descriptor, const char *symbol,
                            void *replacement) {
    IMAGE_THUNK_DATA *names = (IMAGE_THUNK_DATA *) ((unsigned char *) module + descriptor->OriginalFirstThunk);
    IMAGE_THUNK_DATA *addresses = (IMAGE_THUNK_DATA *) ((unsigned char *) module + descriptor->FirstThunk);
    for (; names->u1.AddressOfData; names++, addresses++) {
        IMAGE_IMPORT_BY_NAME *entry;
        if (IMAGE_SNAP_BY_ORDINAL(names->u1.Ordinal)) continue;
        entry = (IMAGE_IMPORT_BY_NAME *) ((unsigned char *) module + names->u1.AddressOfData);
        if (lstrcmpA((const char *) entry->Name, symbol) != 0) continue;
        writePointer((void **) &addresses->u1.Function, replacement);
        return 1;
    }
    return 0;
}

/* Points the import of symbol from library in module at replacement, when module imports it by name. */
static void patchImport(HMODULE module, const char *library, const char *symbol, void *replacement) {
    IMAGE_DOS_HEADER *dos = (IMAGE_DOS_HEADER *) module;
    IMAGE_NT_HEADERS *headers;
    IMAGE_IMPORT_DESCRIPTOR *descriptor;
    DWORD directory;
    if (!module || dos->e_magic != IMAGE_DOS_SIGNATURE) return;
    headers = (IMAGE_NT_HEADERS *) ((unsigned char *) module + dos->e_lfanew);
    if (headers->Signature != IMAGE_NT_SIGNATURE) return;
    directory = headers->OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_IMPORT].VirtualAddress;
    if (!directory) return;
    descriptor = (IMAGE_IMPORT_DESCRIPTOR *) ((unsigned char *) module + directory);
    for (; descriptor->Name; descriptor++) {
        const char *name = (const char *) module + descriptor->Name;
        if (lstrcmpiA(name, library) != 0) continue;
        if (!descriptor->OriginalFirstThunk) continue;
        if (patchImportEntry(module, descriptor, symbol, replacement)) return;
    }
}

/* The SwapBuffers hook. */
static BOOL WINAPI hookSwapBuffers(HDC context) {
    presentFrame();
    return realSwapBuffers(context);
}

/* Hooks the SwapBuffers import and the execSetSpeed slot of module, unless module is this library. */
static void patchLoadedModule(HMODULE module) {
    if (!module || module == self) return;
    patchImport(module, "gdi32.dll", "SwapBuffers", (void *) hookSwapBuffers);
    installSpeedHook(module);
}

/* The LoadLibraryW hook. Patches the module it loads. */
static HMODULE WINAPI hookLoadLibraryW(LPCWSTR name) {
    HMODULE module = realLoadLibraryW(name);
    patchLoadedModule(module);
    return module;
}

/* The LoadLibraryExW hook. Patches the module it loads. */
static HMODULE WINAPI hookLoadLibraryExW(LPCWSTR name, HANDLE file, DWORD flags) {
    HMODULE module = realLoadLibraryExW(name, file, flags);
    patchLoadedModule(module);
    return module;
}

/* Patches module as patchLoadedModule does, and also hooks its LoadLibrary imports, unless module is this library. */
static void patchModule(HMODULE module) {
    if (!module || module == self) return;
    patchLoadedModule(module);
    patchImport(module, "kernel32.dll", "LoadLibraryW", (void *) hookLoadLibraryW);
    patchImport(module, "kernel32.dll", "LoadLibraryExW", (void *) hookLoadLibraryExW);
}

/* Resolves the hooked functions and patches the executable and the engine modules already loaded. */
static void installHooks(void) {
    static const char *modules[] = {"Core.dll", "Engine.dll", "Window.dll", 0};
    HMODULE kernel = GetModuleHandleA("kernel32.dll");
    HMODULE gdi = GetModuleHandleA("gdi32.dll");
    const char **name;
    if (!kernel || !gdi) {
        captureDisabled = 1;
        return;
    }
    realSwapBuffers = (SwapBuffersFunction) GetProcAddress(gdi, "SwapBuffers");
    realLoadLibraryW = (LoadLibraryWFunction) GetProcAddress(kernel, "LoadLibraryW");
    realLoadLibraryExW = (LoadLibraryExWFunction) GetProcAddress(kernel, "LoadLibraryExW");
    if (!realSwapBuffers || !realLoadLibraryW || !realLoadLibraryExW) {
        captureDisabled = 1;
        return;
    }
    patchModule(GetModuleHandleA(0));
    for (name = modules; *name; name++) patchModule(GetModuleHandleA(*name));
}

/* Installs the hooks when the library attaches to the process. */
BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, LPVOID reserved) {
    (void) reserved;
    if (reason == DLL_PROCESS_ATTACH) {
        self = (HMODULE) instance;
        DisableThreadLibraryCalls(instance);
        installHooks();
    }
    return TRUE;
}

#else

typedef void (*SwapWindowFunction)(void *);
typedef void *(*DlopenFunction)(const char *, int);

/* The functions the hooks call on. */
static SwapWindowFunction realSwapWindow;
static DlopenFunction realDlopen;

/* Ignores SIGPIPE when the library loads, so a closed frame socket fails a send instead of ending the process. */
__attribute__((constructor)) static void ignoreBrokenPipe(void) {
    signal(SIGPIPE, SIG_IGN);
}

/* The dlopen interposer. Installs the speed hook into every library it opens. */
void *dlopen(const char *path, int flags) {
    void *handle;
    if (!realDlopen) realDlopen = (DlopenFunction) dlsym(RTLD_NEXT, "dlopen");
    if (!realDlopen) return 0;
    handle = realDlopen(path, flags);
    if (handle && !speedHooked) {
        installSpeedHook(handle);
        dlerror();
    }
    return handle;
}

/* The SDL_GL_SwapWindow interposer. */
void SDL_GL_SwapWindow(void *window) {
    if (!realSwapWindow) realSwapWindow = (SwapWindowFunction) dlsym(RTLD_NEXT, "SDL_GL_SwapWindow");
    presentFrame();
    if (realSwapWindow) realSwapWindow(window);
}

#endif
