/*
 * Hook library loaded into the Unreal Tournament process. It steps the engine clocks by a fixed amount per presented
 * frame, and sends every frame presented while the demo plays to the frame socket as raw 8-bit BGRA pixels. In audio
 * mode it steps the engine clocks by one mixer period of the OpenAL wave output per presented frame instead, and lets
 * the mixer render exactly that period before the next frame.
 */

#ifdef _WIN32
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0600
#endif
#include <winsock2.h>
#include <windows.h>
#else
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <dlfcn.h>
#include <errno.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <pthread.h>
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

/* Highest sample rate UTRECORDER_AUDIO_RATE accepts, and highest period UTRECORDER_AUDIO_PERIOD accepts. */
#define AUDIO_MAX_RATE 192000
#define AUDIO_MAX_PERIOD 8192

/* The part of the file name of the OpenAL Soft library that the mixer clock reads come from on Linux. */
#define OPENAL_LIBRARY "libopenal"

/* Longest wait of one side of the mixer lock, and how long a presented frame waits for the mixer before it gives up. */
#define MIXER_WAIT_MS 50
#define MIXER_STALL_MS 2000

/* Days in each month of a year that is not a leap year. */
static const int MONTH_DAYS[] = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};

/* A variable of which every thread holds its own copy. */
#ifdef _MSC_VER
#define THREAD_LOCAL __declspec(thread)
#else
#define THREAD_LOCAL __thread
#endif

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

/* The frame read last, and the frame read before it that waits to be sent, each pixelWidth by pixelHeight 8-bit BGRA. */
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

/* The frame rate in UTRECORDER_FPS, and the clock steps taken so far. In audio mode, a clock step is one sample. */
static long long clockRate;
static long long clockFrame;

/*
 * The sample rate in UTRECORDER_AUDIO_RATE and the samples of one mixer period in UTRECORDER_AUDIO_PERIOD. Both are set
 * in audio mode only.
 */
static long long audioRate;
static long long audioPeriod;

/* Whether both variables were read. */
static int audioResolved;

/* Clock state: installation tried, clocks patched and stepping, demo started, demo ended. */
static int clockInstalled;
static int clockRunning;
static int clockArmed;
static int clockFinished;

/* Whether the clocks follow the real clock again, and the real seconds at which they started to. */
static int clockReal;
static double clockRealStart;

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

/* Reads audioRate and audioPeriod on the first call. Leaves both 0 unless both variables are set and in range. */
static void resolveAudio(void) {
    if (audioResolved) return;
    audioResolved = 1;
    audioRate = environmentNumber("UTRECORDER_AUDIO_RATE", AUDIO_MAX_RATE);
    audioPeriod = environmentNumber("UTRECORDER_AUDIO_PERIOD", AUDIO_MAX_PERIOD);
    if (!audioRate || !audioPeriod) audioRate = audioPeriod = 0;
}

/* Returns the clock steps in one second. */
static long long clockSteps(void) {
    if (audioRate) return audioRate;
    return clockRate * CLOCK_PREROLL_STEPS;
}

/*
 * Returns the clock steps a presented frame advances the clocks by: one mixer period in audio mode, else one frame while
 * the demo plays and one step before it.
 */
static long long clockStepSize(void) {
    if (audioRate) return audioPeriod;
    return clockArmed ? CLOCK_PREROLL_STEPS : 1;
}

/* Returns the seconds of a monotonic real clock. */
static double readRealSeconds(void) {
#ifdef _WIN32
    LARGE_INTEGER count;
    LARGE_INTEGER frequency;
    QueryPerformanceCounter(&count);
    QueryPerformanceFrequency(&frequency);
    return (double) count.QuadPart / (double) frequency.QuadPart;
#else
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (double) now.tv_sec + (double) now.tv_nsec / 1e9;
#endif
}

/* Returns the real seconds since the clocks follow the real clock again, or 0 while they step. */
static double clockRealElapsed(void) {
    return clockReal ? readRealSeconds() - clockRealStart : 0.0;
}

/* Returns the seconds the clocks have stepped since they were patched, plus the real seconds since they stopped. */
static double clockElapsed(void) {
    return (double) clockFrame / (double) clockSteps() + clockRealElapsed();
}

/* Returns the FTime ticks the clocks have stepped since they were patched, plus the real ticks since they stopped. */
static long long clockTicks(void) {
    return (clockFrame * CLOCK_UNIT) / clockSteps() + (long long) (clockRealElapsed() * (double) CLOCK_UNIT);
}

/* Makes the clocks follow the real clock from where they stepped to, so the engine runs in real time again. */
static void releaseClock(void) {
    if (clockReal) return;
    clockRealStart = readRealSeconds();
    clockReal = 1;
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
#define SYMBOL_SYSTEM_TIME "?appSystemTime@@YAXAAH0000000@Z"
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

/*
 * Hands every site back to the real counter, shifted to carry on from the virtual count, whether the virtual count is
 * ahead of the real counter or behind it. The shift wraps around when the virtual count is behind.
 */
static void releaseCycles(void) {
    if (!clockCyclesArmed) return;
    clockCycleOffset = clockCycleCount - readCycles();
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

/* The lock both sides of the mixer lock hold, and the signal each side waits on. */
static SRWLOCK mixerLock = SRWLOCK_INIT;
static CONDITION_VARIABLE mixerSignal = CONDITION_VARIABLE_INIT;

/* Performance counter ticks in one second, read on the first call of mixerTicksPerSecond. */
static long long mixerFrequency;

/* Takes the mixer lock. */
static void lockMixer(void) {
    AcquireSRWLockExclusive(&mixerLock);
}

/* Gives the mixer lock back. */
static void unlockMixer(void) {
    ReleaseSRWLockExclusive(&mixerLock);
}

/* Wakes every thread waiting in waitMixer. */
static void wakeMixer(void) {
    WakeAllConditionVariable(&mixerSignal);
}

/* Waits in the mixer lock for a wake, for at most millis. Returns 0 when the wait timed out. */
static int waitMixer(int millis) {
    return SleepConditionVariableSRW(&mixerSignal, &mixerLock, (DWORD) millis, 0) || GetLastError() != ERROR_TIMEOUT;
}

/* Returns the ticks of the clock the mixer reads in one second. */
static long long mixerTicksPerSecond(void) {
    LARGE_INTEGER frequency;
    if (!mixerFrequency && QueryPerformanceFrequency(&frequency)) mixerFrequency = frequency.QuadPart;
    return mixerFrequency;
}

/* Returns the real reading of the clock the mixer reads. */
static long long readRealMixer(void) {
    LARGE_INTEGER count;
    QueryPerformanceCounter(&count);
    return count.QuadPart;
}

/* The thread of the last mixer thread started, and the mixer thread being joined, which reads the real clock again. */
static DWORD mixerThread;
static DWORD mixerJoined;

/* Records the calling thread as the last mixer thread started. */
static void markMixerThread(void) {
    mixerThread = GetCurrentThreadId();
    if (mixerJoined == mixerThread) mixerJoined = 0;
}

/* Returns whether the calling thread is the mixer thread being joined. */
static int mixerJoining(void) {
    return mixerJoined && GetCurrentThreadId() == mixerJoined;
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
#define SYMBOL_SYSTEM_TIME "_Z13appSystemTimeRiS_S_S_S_S_S_S_"
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

/* The lock both sides of the mixer lock hold, and the signal each side waits on. */
static pthread_mutex_t mixerLock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t mixerSignal = PTHREAD_COND_INITIALIZER;

/* std::chrono::steady_clock::time_point as i386 System V returns it: through a hidden result pointer. */
typedef struct {
    long long ticks;
} SteadyTime;

typedef SteadyTime (*SteadyNowFunction)(void);

/* The std::chrono::steady_clock::now that the mixer hooks call on. */
static SteadyNowFunction realSteadyNow;

/* Takes the mixer lock. */
static void lockMixer(void) {
    pthread_mutex_lock(&mixerLock);
}

/* Gives the mixer lock back. */
static void unlockMixer(void) {
    pthread_mutex_unlock(&mixerLock);
}

/* Wakes every thread waiting in waitMixer. */
static void wakeMixer(void) {
    pthread_cond_broadcast(&mixerSignal);
}

/* Waits in the mixer lock for a wake, for at most millis. Returns 0 when the wait timed out. */
static int waitMixer(int millis) {
    struct timespec deadline;
    clock_gettime(CLOCK_REALTIME, &deadline);
    deadline.tv_nsec += millis * 1000000L;
    deadline.tv_sec += deadline.tv_nsec / 1000000000L;
    deadline.tv_nsec %= 1000000000L;
    return pthread_cond_timedwait(&mixerSignal, &mixerLock, &deadline) != ETIMEDOUT;
}

/* Returns the ticks of the clock the mixer reads in one second. */
static long long mixerTicksPerSecond(void) {
    return 1000000000LL;
}

/* Returns the reading of the real std::chrono::steady_clock::now, or a zero reading when it cannot be resolved. */
static SteadyTime readRealSteady(void) {
    SteadyTime zero = {0};
    if (!realSteadyNow) realSteadyNow = (SteadyNowFunction) dlsym(RTLD_NEXT, "_ZNSt6chrono3_V212steady_clock3nowEv");
    return realSteadyNow ? realSteadyNow() : zero;
}

/* Returns the real reading of the clock the mixer reads. */
static long long readRealMixer(void) {
    return readRealSteady().ticks;
}

/* Does nothing, since a mixer thread being joined leaves its sleep on its own. */
static void markMixerThread(void) {
}

/* Returns 0, since a mixer thread being joined leaves its sleep on its own. */
static int mixerJoining(void) {
    return 0;
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
 * Mixer lock state: a mixer thread has read the mixer clock, the mixer runs in lockstep with the presented frames, and
 * the mixer reads the real clock again for good.
 */
static int mixerStarted;
static int mixerLocked;
static int mixerReleased;

/* The reading at the first read of the current mixer thread, and the last reading it got, before the lock. */
static long long mixerThreadStart;
static long long mixerLastReading;

/* The reading the mixer clock counts on from, and the clock step at which it was that reading. */
static long long mixerBaseTicks;
static long long mixerBaseFrame;

/* The amount added to the real reading outside the lock. */
static long long mixerOffset;

/* The clock step at which the wav output begins. */
static long long waveStart;

/* The clock step of the last mixer read, and the clock step at which the mixer last went idle. */
static long long mixerRead;
static long long mixerIdle = -1;

/* The mixer clock reads of the calling thread. Above 0 on a mixer thread only. */
static THREAD_LOCAL long long mixerThreadReads;

/*
 * Returns the mixer clock at clock step frame: mixerBaseTicks, plus the samples since mixerBaseFrame. Unless start is
 * set, the reading falls half a sample later.
 */
static long long mixerClock(long long frame, int start) {
    long long halfSamples = (frame - mixerBaseFrame) * 2 + (start ? 0 : 1);
    return mixerBaseTicks + halfSamples * mixerTicksPerSecond() / (audioRate * 2);
}

/*
 * Returns the reading a mixer thread gets outside the lock: the real reading real, shifted by mixerOffset. Remembers
 * the first and the last reading of the current mixer thread, where start marks its first read.
 */
static long long readFreeMixer(long long real, int start) {
    long long reading = real + mixerOffset;
    if (mixerJoining()) return reading;
    if (start) mixerThreadStart = reading;
    mixerLastReading = reading;
    return reading;
}

/*
 * Returns the reading a mixer thread gets in the lock: the mixer clock of the current clock step, which moves only when
 * a presented frame advances the clocks. The first read of a mixer thread is exactly on the step and starts its wav
 * output. Every later read falls half a sample after the step, so the mixer counts every period up to the step as due
 * and none after it.
 */
static long long readLockedMixer(long long real, int start) {
    if (!mixerStarted) {
        mixerBaseTicks = real;
        mixerBaseFrame = clockFrame;
    }
    if (start) {
        waveStart = clockFrame;
        mixerIdle = -1;
        fprintf(stderr, "uthooks: mixer started in the lock at clock sample %lld\n", clockFrame);
        fflush(stderr);
    }
    mixerRead = clockFrame;
    return mixerClock(clockFrame, start);
}

/*
 * Returns the clock the mixer thread reads in place of the real reading real: the mixer clock while the mixer is locked,
 * and the shifted real clock before the lock, after the release, and on a mixer thread being joined.
 */
static long long readMixerClock(long long real) {
    long long reading;
    int start = mixerThreadReads++ == 0;
    lockMixer();
    if (start) markMixerThread();
    if (mixerLocked && !mixerReleased && !mixerJoining()) {
        reading = readLockedMixer(real, start);
    } else {
        reading = readFreeMixer(real, start);
    }
    mixerStarted = 1;
    unlockMixer();
    return reading;
}

/*
 * Holds a mixer thread in place of a sleep. Marks the mixer idle at its last read, since it sleeps only once it has
 * rendered every period due then, and waits until a presented frame advances the clocks past that read. Returns 0
 * without waiting when the calling thread is no mixer thread, is being joined, or the mixer is not locked, so the caller
 * sleeps for real.
 */
static int holdMixer(void) {
    int held;
    if (!mixerThreadReads || mixerJoining()) return 0;
    lockMixer();
    held = mixerLocked && !mixerReleased;
    if (held) {
        mixerIdle = mixerRead;
        wakeMixer();
        if (clockFrame == mixerRead) waitMixer(MIXER_WAIT_MS);
    }
    unlockMixer();
    return held;
}

/*
 * Locks the mixer to the presented frames in audio mode. The wav position at the current clock step is every whole
 * period the current mixer thread has due on the real clock, so the lock carries on from what the mixer has rendered.
 */
static void engageMixer(void) {
    long long reading;
    long long due;
    if (!audioRate) return;
    lockMixer();
    if (!mixerLocked && !mixerReleased) {
        mixerLocked = 1;
        if (mixerStarted) {
            reading = readRealMixer() + mixerOffset;
            if (reading < mixerLastReading) reading = mixerLastReading;
            due = (reading - mixerThreadStart) * audioRate / mixerTicksPerSecond();
            due -= due % audioPeriod;
            mixerBaseTicks = mixerThreadStart;
            mixerBaseFrame = clockFrame - due;
            waveStart = mixerBaseFrame;
            mixerIdle = -1;
        }
        wakeMixer();
        fprintf(stderr, "uthooks: mixer locked at wave sample %lld\n", clockFrame - waveStart);
        fflush(stderr);
    }
    unlockMixer();
}

/* Hands the mixer back to the real clock in audio mode, shifted to carry on from the mixer clock, and reports why. */
static void releaseMixer(const char *reason) {
    if (!audioRate) return;
    lockMixer();
    if (!mixerReleased) {
        mixerReleased = 1;
        if (mixerLocked && mixerStarted) mixerOffset = mixerClock(clockFrame, 0) - readRealMixer();
        wakeMixer();
        fprintf(stderr, "uthooks: mixer released at wave sample %lld, %s\n", clockFrame - waveStart, reason);
        fflush(stderr);
    }
    unlockMixer();
}

/*
 * Advances the clocks by one mixer period and, while the mixer is locked, waits until the mixer has rendered it.
 * Releases the mixer when it does not go idle within MIXER_STALL_MS.
 */
static void stepMixer(void) {
    int waited = 0;
    lockMixer();
    clockFrame += audioPeriod;
    wakeMixer();
    while (mixerLocked && mixerStarted && !mixerReleased && mixerIdle != clockFrame && waited < MIXER_STALL_MS) {
        if (!waitMixer(MIXER_WAIT_MS)) waited += MIXER_WAIT_MS;
    }
    unlockMixer();
    if (waited >= MIXER_STALL_MS) releaseMixer("the mixer stalled");
}

/* Returns the days in month, counted from 0, of year. */
static int monthDays(int year, int month) {
    int leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
    return MONTH_DAYS[month] + (month == 1 && leap);
}

/* Returns the days in year. */
static int yearDays(int year) {
    return monthDays(year, 1) + 337;
}

/*
 * The appSystemTime hook in audio mode. Reports the position of the wav output as a date, counted from 2000-01-01
 * 00:00:00.000, a Saturday. Reports that date itself until the mixer is locked.
 */
static void hookSystemTime(int *year, int *month, int *dayOfWeek, int *day, int *hour, int *minute, int *second,
                           int *millisecond) {
    long long millis;
    long long days;
    lockMixer();
    millis = mixerLocked ? (clockFrame - waveStart) * 1000 / audioRate : 0;
    unlockMixer();
    days = millis / 86400000;
    *millisecond = (int) (millis % 1000);
    *second = (int) (millis / 1000 % 60);
    *minute = (int) (millis / 60000 % 60);
    *hour = (int) (millis / 3600000 % 24);
    *dayOfWeek = (int) ((days + 6) % 7);
    for (*year = 2000; days >= yearDays(*year); (*year)++) days -= yearDays(*year);
    for (*month = 0; days >= monthDays(*year, *month); (*month)++) days -= monthDays(*year, *month);
    (*month)++;
    *day = (int) days + 1;
}

/*
 * Sets the cycle scale so the real cycles between two presented frames count as one clock step size while the demo
 * plays or in audio mode, and as clockPreroll otherwise. Does nothing while the sites read the virtual count, after the
 * demo, or without inlined sites.
 */
static void scaleCycles(void) {
    unsigned long long now;
    unsigned long long span;
    if (!CLOCK_INLINED || !clockCycleScaleLong || clockCyclesArmed || clockFinished) return;
    now = readCycles();
    if (clockPreviousCycles) {
        span = now - clockPreviousCycles;
        if (!clockArmed && !audioRate) {
            *clockCycleScaleLong = clockPreroll;
        } else if (span > 0) {
            *clockCycleScaleLong = (double) clockStepSize() / ((double) clockSteps() * (double) span);
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
 * Ends the capture: releases the cycle sites to the real counter when they have not released themselves already,
 * releases the engine clocks and the mixer to the real clock, drops the pending frame, closes stream and reports the
 * frame count.
 */
static void finishCapture(void) {
    clockFinished = 1;
    releaseCycles();
    releaseClock();
    releaseMixer("the demo ended");
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

/* Arms the clock when the demo starts once the clock is installed, and finishes the capture when the demo stops. */
static void trackDemo(void) {
    if (clockFinished || !clockInstalled) return;
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

/* The execSetSpeed hook. Arms the capture on the first call before the demo ends, and locks the mixer in audio mode. */
static void SET_SPEED_ABI hookSetSpeed(SET_SPEED_ARGS) {
    if (!captureArmed && !clockFinished) {
        captureArmed = 1;
        fprintf(stderr, "uthooks: capture armed at frame %lld\n", presentCount);
        fflush(stderr);
        engageMixer();
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
 * Patches the Core clock functions and the frame delta sites to step at the frame rate in UTRECORDER_FPS, or in audio
 * mode by one mixer period per frame, with appSystemTime reporting the wav position. Leaves the clocks alone and
 * releases the mixer when neither is set or a required clock function is missing.
 */
static void installClock(void) {
    int patched = 0;
    int expected = 4;
    clockInstalled = 1;
    clockRate = environmentNumber("UTRECORDER_FPS", CLOCK_MAX_RATE);
    resolveAudio();
    if ((!clockRate && !audioRate) || missingClock()) {
        releaseMixer("the engine clock is not stepped");
        return;
    }
    patched += patchSeconds(SYMBOL_NEW, &clockBaseNew, (void *) hookNew);
    patched += patchSeconds(SYMBOL_NOCHECK, &clockBaseNoCheck, (void *) hookNoCheck);
    patched += patchTime(SYMBOL_SECONDS, &clockBaseSeconds, (void *) hookSeconds);
    patched += patchTime(SYMBOL_SLOW, &clockBaseSlow, (void *) hookSlow);
    if (coreSymbol(SYMBOL_COMPAT)) {
        expected++;
        patched += patchSeconds(SYMBOL_COMPAT, &clockBaseCompat, (void *) hookCompat);
    }
    if (audioRate) {
        expected++;
        patched += coreSymbol(SYMBOL_SYSTEM_TIME) && installJump(coreSymbol(SYMBOL_SYSTEM_TIME), (void *) hookSystemTime);
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
    if (audioRate) {
        fprintf(stderr, "uthooks: engine clock stepping %lld samples per frame at %lld Hz\n", audioPeriod, audioRate);
    } else {
        fprintf(stderr, "uthooks: engine clock stepping %lld frames per second\n", clockRate);
    }
}

/*
 * Steps the clocks by one frame while the demo plays, and by one step before it. In audio mode, steps them by one mixer
 * period and waits for the mixer to render it. Does nothing once the clocks follow the real clock again.
 */
static void advanceClock(void) {
    if (!clockRunning || clockReal) return;
    if (audioRate) {
        stepMixer();
    } else {
        clockFrame += clockStepSize();
    }
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

/* Returns whether the clock is due to be installed: at once in frame mode, and once the capture is armed in audio mode. */
static int clockDue(void) {
    resolveAudio();
    return !audioRate || captureArmed;
}

/* Runs before every presented frame: installs the clock when due, tracks the demo and captures the frame. */
static void presentFrame(void) {
    if (!clockInstalled && clockDue()) installClock();
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

/* Whether the imports of OpenAL32.dll are hooked. */
static int mixerHooked;

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

/* The QueryPerformanceCounter hook of OpenAL32.dll. Replaces the reading with the mixer clock in audio mode. */
static BOOL WINAPI hookPerformanceCounter(LARGE_INTEGER *count) {
    BOOL result = QueryPerformanceCounter(count);
    resolveAudio();
    if (result && audioRate) count->QuadPart = readMixerClock(count->QuadPart);
    return result;
}

/* The Sleep hook of OpenAL32.dll. Holds a mixer thread instead of sleeping while the mixer is locked. */
static VOID WINAPI hookSleep(DWORD millis) {
    if (!holdMixer()) Sleep(millis);
}

/*
 * Releases the last mixer thread started to the real clock, shifted to carry on from the mixer clock, when handle is that
 * thread and the mixer is locked. Its wake keeps the mixer thread from being held, so it leaves its sleep and sees that
 * it is being stopped.
 */
static void releaseJoinedMixer(HANDLE handle) {
    DWORD thread = GetThreadId(handle);
    if (!thread) return;
    lockMixer();
    if (thread == mixerThread && mixerLocked && !mixerReleased) {
        mixerJoined = thread;
        mixerOffset = mixerClock(clockFrame, 0) - readRealMixer();
        wakeMixer();
        fprintf(stderr, "uthooks: mixer joined at wave sample %lld\n", clockFrame - waveStart);
        fflush(stderr);
    }
    unlockMixer();
}

/* The WaitForSingleObject hook of OpenAL32.dll. Releases the mixer thread it waits for. */
static DWORD WINAPI hookWait(HANDLE handle, DWORD millis) {
    releaseJoinedMixer(handle);
    return WaitForSingleObject(handle, millis);
}

/* The WaitForSingleObjectEx hook of OpenAL32.dll. Releases the mixer thread it waits for. */
static DWORD WINAPI hookWaitEx(HANDLE handle, DWORD millis, BOOL alertable) {
    releaseJoinedMixer(handle);
    return WaitForSingleObjectEx(handle, millis, alertable);
}

/*
 * Points the QueryPerformanceCounter, Sleep, WaitForSingleObject and WaitForSingleObjectEx imports of OpenAL32.dll at
 * their hooks, once it is loaded.
 */
static void patchMixer(void) {
    HMODULE openAl;
    if (mixerHooked) return;
    openAl = GetModuleHandleA("OpenAL32.dll");
    if (!openAl) return;
    mixerHooked = 1;
    patchImport(openAl, "kernel32.dll", "QueryPerformanceCounter", (void *) hookPerformanceCounter);
    patchImport(openAl, "kernel32.dll", "Sleep", (void *) hookSleep);
    patchImport(openAl, "kernel32.dll", "WaitForSingleObject", (void *) hookWait);
    patchImport(openAl, "kernel32.dll", "WaitForSingleObjectEx", (void *) hookWaitEx);
}

/*
 * Hooks the SwapBuffers import and the execSetSpeed slot of module, unless module is this library, and the imports of
 * OpenAL32.dll once it is loaded.
 */
static void patchLoadedModule(HMODULE module) {
    patchMixer();
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

typedef int (*NanosleepFunction)(const struct timespec *, struct timespec *);

/* The nanosleep that the interposer calls on. */
static NanosleepFunction realNanosleep;

/* Returns whether code at address belongs to the OpenAL Soft library. */
static int inOpenAl(void *address) {
    Dl_info info;
    if (!dladdr(address, &info) || !info.dli_fname) return 0;
    return strstr(info.dli_fname, OPENAL_LIBRARY) != 0;
}

/*
 * The std::chrono::steady_clock::now interposer. Replaces the reading with the mixer clock in audio mode when OpenAL
 * Soft reads the clock, which it does in its mixer threads only.
 */
SteadyTime _ZNSt6chrono3_V212steady_clock3nowEv(void) {
    SteadyTime time = readRealSteady();
    resolveAudio();
    if (audioRate && inOpenAl(__builtin_return_address(0))) time.ticks = readMixerClock(time.ticks);
    return time;
}

/* The nanosleep interposer. Holds a mixer thread instead of sleeping while the mixer is locked. */
int nanosleep(const struct timespec *request, struct timespec *remaining) {
    if (holdMixer()) return 0;
    if (!realNanosleep) realNanosleep = (NanosleepFunction) dlsym(RTLD_NEXT, "nanosleep");
    if (!realNanosleep) return -1;
    return realNanosleep(request, remaining);
}

#endif
