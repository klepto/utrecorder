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

#define CONNECT_ATTEMPTS 200
#define CONNECT_DELAY_MS 50
#define SYMBOL_SET_SPEED "intUDemoInterfaceexecSetSpeed"
#define CLOCK_UNIT 4294967296LL
#define CLOCK_MAX_RATE 1000
#define CLOCK_PREROLL_STEPS 20

#ifdef _WIN32
#define GL_ENTRY WINAPI
#else
#define GL_ENTRY
#endif

typedef void *(*GetProcAddressFunction)(const char *);
typedef void(GL_ENTRY *GetIntegerv)(unsigned int, int *);
typedef void(GL_ENTRY *ReadPixels)(int, int, int, int, unsigned int, unsigned int, void *);

static GetIntegerv readViewport;
static ReadPixels readPixels;
static unsigned char *pixels;
static unsigned char *pending;
static size_t pendingSize;
static int pixelWidth;
static int pixelHeight;
static int disabled;

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

static int capturePort(void) {
    const char *value = getenv("UTRECORDER_PORT");
    int port;
    if (!value) return 0;
    port = atoi(value);
    if (port <= 0 || port > 65535) return 0;
    return port;
}

static void openStream(void) {
    struct sockaddr_in address;
    int attempt;
    int port = capturePort();
    int nodelay = 1;
    if (!port) {
        disabled = 1;
        return;
    }
#ifdef _WIN32
    {
        WSADATA startup;
        if (WSAStartup(MAKEWORD(2, 2), &startup) != 0) {
            disabled = 1;
            return;
        }
    }
#endif
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
    disabled = 1;
}

static int writeAll(const unsigned char *data, size_t size) {
    while (size > 0) {
        int chunk = size > 0x100000 ? 0x100000 : (int) size;
        int written = send(stream, (const char *) data, chunk, 0);
        if (written <= 0) return 0;
        data += written;
        size -= (size_t) written;
    }
    return 1;
}

static void resolveGl(void) {
#ifdef _WIN32
    HMODULE library = GetModuleHandleA("opengl32.dll");
    if (!library) library = LoadLibraryA("opengl32.dll");
    if (!library) {
        disabled = 1;
        return;
    }
    readViewport = (GetIntegerv) GetProcAddress(library, "glGetIntegerv");
    readPixels = (ReadPixels) GetProcAddress(library, "glReadPixels");
#else
    GetProcAddressFunction lookup = (GetProcAddressFunction) dlsym(RTLD_NEXT, "SDL_GL_GetProcAddress");
    if (!lookup) {
        disabled = 1;
        return;
    }
    readViewport = (GetIntegerv) lookup("glGetIntegerv");
    readPixels = (ReadPixels) lookup("glReadPixels");
#endif
    if (!readViewport || !readPixels) disabled = 1;
}

static void sendPending(void) {
    if (!pendingSize) return;
    if (stream == STREAM_CLOSED) openStream();
    if (disabled) return;
    if (!writeAll(pending, pendingSize)) {
        closeStream(stream);
        stream = STREAM_CLOSED;
        disabled = 1;
    }
}

static void captureFrame(void) {
    int viewport[4];
    size_t size;
    unsigned char *held;
    readViewport(GL_VIEWPORT, viewport);
    if (viewport[2] <= 0 || viewport[3] <= 0) return;
    if (viewport[2] != pixelWidth || viewport[3] != pixelHeight) {
        free(pixels);
        free(pending);
        pixelWidth = viewport[2];
        pixelHeight = viewport[3];
        pendingSize = 0;
        pixels = malloc((size_t) pixelWidth * pixelHeight * 4);
        pending = malloc((size_t) pixelWidth * pixelHeight * 4);
        if (!pixels || !pending) {
            disabled = 1;
            return;
        }
    }
    size = (size_t) pixelWidth * pixelHeight * 4;
    readPixels(0, 0, pixelWidth, pixelHeight, GL_BGRA, GL_UNSIGNED_BYTE, pixels);
    sendPending();
    if (disabled) return;
    held = pending;
    pending = pixels;
    pixels = held;
    pendingSize = size;
}

static double clockBaseNew;
static double clockBaseNoCheck;
static double clockBaseCompat;
static long long clockBaseSeconds;
static long long clockBaseSlow;
static long long clockFrame;
static long long clockRate;
static float *clockCycleScale;
static double *clockCycleScaleLong;
static unsigned long long clockCycles;
static double clockPreroll;
static int clockArmed;
static int clockFinished;
static int clockInstalled;
static int clockRunning;
static int captureArmed;
static int speedHooked;
static long long presentCount;
static long long demoFrames;
static int *demoFlag;
static int demoResolved;

static long long clockSteps(void) {
    return clockRate * CLOCK_PREROLL_STEPS;
}

static double clockElapsed(void) {
    return (double) clockFrame / (double) clockSteps();
}

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
#define MODULE_DEMO "udemo.dll"
#define SET_SPEED_ABI __fastcall
#define SET_SPEED_ARGS void *self, void *held, void *stack, void *result
#define SET_SPEED_PASS self, held, stack, result
#define CLOCK_INLINED 1

typedef long long *(__cdecl *TimeFunction)(long long *);
typedef double(__cdecl *SecondsFunction)(void);

static long long *__cdecl hookSeconds(long long *result) {
    *result = clockBaseSeconds + clockTicks();
    return result;
}

static long long *__cdecl hookSlow(long long *result) {
    *result = clockBaseSlow + clockTicks();
    return result;
}

static long long readTime(TimeFunction original) {
    long long value = 0;
    original(&value);
    return value;
}

static void *coreSymbol(const char *name) {
    HMODULE core = GetModuleHandleA("Core.dll");
    if (!core) return 0;
    return (void *) GetProcAddress(core, name);
}

static void *moduleSymbol(void *module, const char *name) {
    if (!module) return 0;
    return (void *) GetProcAddress((HMODULE) module, name);
}

static void *findDemoModule(void) {
    return (void *) GetModuleHandleA(MODULE_DEMO);
}

static int writePointer(void **slot, void *value) {
    DWORD protection;
    if (!VirtualProtect(slot, sizeof(value), PAGE_READWRITE, &protection)) return 0;
    *slot = value;
    VirtualProtect(slot, sizeof(value), protection, &protection);
    return 1;
}

static int writeCode(void *target, const unsigned char *code, size_t size) {
    DWORD protection;
    if (!VirtualProtect(target, size, PAGE_EXECUTE_READWRITE, &protection)) return 0;
    memcpy(target, code, size);
    VirtualProtect(target, size, protection, &protection);
    FlushInstructionCache(GetCurrentProcess(), target, size);
    return 1;
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
#define MODULE_DEMO "udemo.so"
#define SET_SPEED_ABI
#define SET_SPEED_ARGS void *self, void *stack, void *result
#define SET_SPEED_PASS self, stack, result
#define CLOCK_INLINED 0

typedef long long (*TimeFunction)(void);
typedef double (*SecondsFunction)(void);

static void *coreLibrary;

static long long hookSeconds(void) {
    return clockBaseSeconds + clockTicks();
}

static long long hookSlow(void) {
    return clockBaseSlow + clockTicks();
}

static long long readTime(TimeFunction original) {
    return original();
}

static void *coreSymbol(const char *name) {
    void *symbol = dlsym(RTLD_DEFAULT, name);
    if (symbol) return symbol;
    if (!coreLibrary) coreLibrary = dlopen("Core.so", RTLD_NOLOAD | RTLD_LAZY);
    if (!coreLibrary) coreLibrary = dlopen("libCore.so", RTLD_NOLOAD | RTLD_LAZY);
    if (!coreLibrary) return 0;
    return dlsym(coreLibrary, name);
}

static void *moduleSymbol(void *module, const char *name) {
    if (!module) return 0;
    return dlsym(module, name);
}

static void *findDemoModule(void) {
    void *module = dlopen(MODULE_DEMO, RTLD_NOLOAD | RTLD_LAZY);
    if (!module) module = dlopen("libudemo.so", RTLD_NOLOAD | RTLD_LAZY);
    return module;
}

static int writePointer(void **slot, void *value) {
    long granularity = sysconf(_SC_PAGESIZE);
    char *page = (char *) ((uintptr_t) slot & ~(uintptr_t) (granularity - 1));
    size_t span = (size_t) ((char *) slot + sizeof(value) - page);
    if (mprotect(page, span, PROT_READ | PROT_WRITE) != 0) return 0;
    *slot = value;
    return 1;
}

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

#endif

static double hookNew(void) {
    return clockBaseNew + clockElapsed();
}

static double hookNoCheck(void) {
    return clockBaseNoCheck + clockElapsed();
}

static double hookCompat(void) {
    return clockBaseCompat + clockElapsed();
}

static int installJump(void *target, void *replacement) {
    unsigned char code[16];
    size_t size;
#if defined(__x86_64__) || defined(_M_X64)
    code[0] = 0x48;
    code[1] = 0xB8;
    memcpy(code + 2, &replacement, sizeof(replacement));
    code[10] = 0xFF;
    code[11] = 0xE0;
    size = 12;
#else
    int offset = (int) ((char *) replacement - (char *) target - 5);
    code[0] = 0xE9;
    memcpy(code + 1, &offset, sizeof(offset));
    size = 5;
#endif
    return writeCode(target, code, size);
}

static int patchTime(const char *name, long long *base, void *replacement) {
    TimeFunction original = (TimeFunction) coreSymbol(name);
    if (!original) return 0;
    *base = readTime(original);
    return installJump((void *) original, replacement);
}

static int patchSeconds(const char *name, double *base, void *replacement) {
    SecondsFunction original = (SecondsFunction) coreSymbol(name);
    if (!original) return 0;
    *base = original();
    return installJump((void *) original, replacement);
}

static void clearTimestampGates(void) {
    static const char *const gates[] = {"GTimestampPreferred", "GTGTPreferred", 0};
    const char *const *gate;
    int *flag;
    for (gate = gates; *gate; gate++) {
        flag = (int *) coreSymbol(*gate);
        if (flag) *flag = 0;
    }
}

static void scaleCycles(void) {
    unsigned long long now;
    unsigned long long span;
    if (!CLOCK_INLINED || !clockCycleScaleLong) return;
    now = readCycles();
    if (clockCycles) {
        span = now - clockCycles;
        if (!clockArmed) {
            *clockCycleScaleLong = clockPreroll;
        } else if (span > 0) {
            *clockCycleScaleLong = 1.0 / ((double) clockRate * (double) span);
        }
        if (clockCycleScale) *clockCycleScale = (float) *clockCycleScaleLong;
    }
    clockCycles = now;
}

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

static void finishCapture(void) {
    clockFinished = 1;
    pendingSize = 0;
    if (stream != STREAM_CLOSED) {
        closeStream(stream);
        stream = STREAM_CLOSED;
    }
    disabled = 1;
    fprintf(stderr, "uthooks: demo ended after %lld frames, kept %lld\n", demoFrames,
            demoFrames > 2 ? demoFrames - 2 : 0);
    fflush(stderr);
}

static void trackDemo(void) {
    if (clockFinished) return;
    if (!clockArmed) {
        if (!demoPlaying()) return;
        clockArmed = 1;
        fprintf(stderr, "uthooks: demo started at frame %lld\n", presentCount);
        fflush(stderr);
        return;
    }
    if (!demoPlaying()) finishCapture();
}

typedef void(SET_SPEED_ABI *SetSpeedFunction)(SET_SPEED_ARGS);

static SetSpeedFunction realSetSpeed;

static void SET_SPEED_ABI hookSetSpeed(SET_SPEED_ARGS) {
    if (!captureArmed && !clockFinished) {
        captureArmed = 1;
        fprintf(stderr, "uthooks: capture armed at frame %lld\n", presentCount);
        fflush(stderr);
    }
    if (realSetSpeed) realSetSpeed(SET_SPEED_PASS);
}

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

static void findSpeedHook(void) {
    if (speedHooked) return;
    installSpeedHook(findDemoModule());
}

static long long captureRate(void) {
    const char *value = getenv("UTRECORDER_FPS");
    long long rate;
    if (!value) return 0;
    rate = atoi(value);
    if (rate <= 0 || rate > CLOCK_MAX_RATE) return 0;
    return rate;
}

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

static void installClock(void) {
    int patched = 0;
    int expected = 4;
    clockInstalled = 1;
    clockRate = captureRate();
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
    }
    scaleCycles();
    clockRunning = 1;
    if (patched < expected) {
        fprintf(stderr, "uthooks: patched %d of %d engine clocks, playback may drift\n", patched, expected);
    }
    fprintf(stderr, "uthooks: engine clock stepping %lld frames per second\n", clockRate);
}

static void advanceClock(void) {
    if (!clockRunning) return;
    clockFrame += clockArmed ? CLOCK_PREROLL_STEPS : 1;
    scaleCycles();
}

static void presentFrame(void) {
    if (!clockInstalled) installClock();
    presentCount++;
    findSpeedHook();
    trackDemo();
    if (captureArmed && !clockFinished) {
        demoFrames++;
        if (demoFrames > 1 && !disabled) {
            if (!readPixels) resolveGl();
            if (!disabled) captureFrame();
        }
    }
    advanceClock();
}

#ifdef _WIN32

typedef BOOL(WINAPI *SwapBuffersFunction)(HDC);
typedef HMODULE(WINAPI *LoadLibraryAFunction)(LPCSTR);
typedef HMODULE(WINAPI *LoadLibraryWFunction)(LPCWSTR);
typedef HMODULE(WINAPI *LoadLibraryExWFunction)(LPCWSTR, HANDLE, DWORD);

static SwapBuffersFunction realSwapBuffers;
static LoadLibraryAFunction realLoadLibraryA;
static LoadLibraryWFunction realLoadLibraryW;
static LoadLibraryExWFunction realLoadLibraryExW;
static HMODULE self;

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
        IMAGE_THUNK_DATA *names;
        IMAGE_THUNK_DATA *addresses;
        if (lstrcmpiA(name, library) != 0) continue;
        if (!descriptor->OriginalFirstThunk) continue;
        names = (IMAGE_THUNK_DATA *) ((unsigned char *) module + descriptor->OriginalFirstThunk);
        addresses = (IMAGE_THUNK_DATA *) ((unsigned char *) module + descriptor->FirstThunk);
        for (; names->u1.AddressOfData; names++, addresses++) {
            IMAGE_IMPORT_BY_NAME *entry;
            DWORD protection;
            if (IMAGE_SNAP_BY_ORDINAL(names->u1.Ordinal)) continue;
            entry = (IMAGE_IMPORT_BY_NAME *) ((unsigned char *) module + names->u1.AddressOfData);
            if (lstrcmpA((const char *) entry->Name, symbol) != 0) continue;
            if (!VirtualProtect(&addresses->u1.Function, sizeof(void *), PAGE_READWRITE, &protection)) return;
            addresses->u1.Function = (ULONG_PTR) replacement;
            VirtualProtect(&addresses->u1.Function, sizeof(void *), protection, &protection);
            return;
        }
    }
}

static BOOL WINAPI hookSwapBuffers(HDC context) {
    presentFrame();
    return realSwapBuffers(context);
}

static void patchRenderer(HMODULE module) {
    if (!module || module == self) return;
    patchImport(module, "gdi32.dll", "SwapBuffers", (void *) hookSwapBuffers);
    installSpeedHook(module);
}

static HMODULE WINAPI hookLoadLibraryA(LPCSTR name) {
    HMODULE module = realLoadLibraryA(name);
    patchRenderer(module);
    return module;
}

static HMODULE WINAPI hookLoadLibraryW(LPCWSTR name) {
    HMODULE module = realLoadLibraryW(name);
    patchRenderer(module);
    return module;
}

static HMODULE WINAPI hookLoadLibraryExW(LPCWSTR name, HANDLE file, DWORD flags) {
    HMODULE module = realLoadLibraryExW(name, file, flags);
    patchRenderer(module);
    return module;
}

static void patchModule(HMODULE module) {
    if (!module || module == self) return;
    patchRenderer(module);
    patchImport(module, "kernel32.dll", "LoadLibraryA", (void *) hookLoadLibraryA);
    patchImport(module, "kernel32.dll", "LoadLibraryW", (void *) hookLoadLibraryW);
    patchImport(module, "kernel32.dll", "LoadLibraryExW", (void *) hookLoadLibraryExW);
}

static void installHooks(void) {
    static const char *modules[] = {
        "Core.dll", "Engine.dll", "Window.dll", "OpenGlDrv.dll", "XOpenGLDrv.dll", 0
    };
    HMODULE kernel = GetModuleHandleA("kernel32.dll");
    HMODULE gdi = GetModuleHandleA("gdi32.dll");
    const char **name;
    if (!kernel || !gdi) {
        disabled = 1;
        return;
    }
    realSwapBuffers = (SwapBuffersFunction) GetProcAddress(gdi, "SwapBuffers");
    realLoadLibraryA = (LoadLibraryAFunction) GetProcAddress(kernel, "LoadLibraryA");
    realLoadLibraryW = (LoadLibraryWFunction) GetProcAddress(kernel, "LoadLibraryW");
    realLoadLibraryExW = (LoadLibraryExWFunction) GetProcAddress(kernel, "LoadLibraryExW");
    if (!realSwapBuffers || !realLoadLibraryA || !realLoadLibraryW || !realLoadLibraryExW) {
        disabled = 1;
        return;
    }
    patchModule(GetModuleHandleA(0));
    for (name = modules; *name; name++) patchModule(GetModuleHandleA(*name));
}

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

typedef void (*SwapWindow)(void *);
typedef void *(*OpenLibrary)(const char *, int);

static SwapWindow realSwap;
static OpenLibrary realOpen;

__attribute__((constructor)) static void ignoreBrokenPipe(void) {
    signal(SIGPIPE, SIG_IGN);
}

void *dlopen(const char *path, int flags) {
    void *handle;
    if (!realOpen) realOpen = (OpenLibrary) dlsym(RTLD_NEXT, "dlopen");
    if (!realOpen) return 0;
    handle = realOpen(path, flags);
    if (handle && !speedHooked) installSpeedHook(handle);
    return handle;
}

void SDL_GL_SwapWindow(void *window) {
    if (!realSwap) realSwap = (SwapWindow) dlsym(RTLD_NEXT, "SDL_GL_SwapWindow");
    presentFrame();
    if (realSwap) realSwap(window);
}

#endif
