/*
 * Injector that starts a program suspended, loads a library into it, then resumes it and waits for it to exit.
 *
 * Usage: utinject <library> <executable> [arguments...]. The exit code is the program's own, or 2 when the usage is
 * wrong, the library path cannot be resolved, the program cannot be started or the library cannot be loaded into it.
 */

#include <windows.h>
#include <stdio.h>
#include <stdlib.h>

/* Returns 1 when value has to be quoted to reach the program as one argument, 0 otherwise. */
static int needsQuotes(const wchar_t *value) {
    const wchar_t *scan;
    if (!*value) return 1;
    for (scan = value; *scan; scan++) {
        if (*scan == L' ' || *scan == L'\t' || *scan == L'"') return 1;
    }
    return 0;
}

/* Writes count backslashes to target at offset and returns the offset past them. */
static size_t appendBackslashes(wchar_t *target, size_t offset, size_t count) {
    size_t index;
    for (index = 0; index < count; index++) target[offset++] = L'\\';
    return offset;
}

/*
 * Writes value to target at offset as one command line argument, quoted and escaped when needed, and returns the
 * offset past it.
 */
static size_t appendArgument(wchar_t *target, size_t offset, const wchar_t *value) {
    const wchar_t *scan;
    size_t backslashes = 0;
    int quoted = needsQuotes(value);
    if (quoted) target[offset++] = L'"';
    for (scan = value; *scan; scan++) {
        if (*scan == L'\\') {
            backslashes++;
            target[offset++] = *scan;
            continue;
        }
        if (*scan == L'"') offset = appendBackslashes(target, offset, backslashes + 1);
        backslashes = 0;
        target[offset++] = *scan;
    }
    if (quoted) {
        offset = appendBackslashes(target, offset, backslashes);
        target[offset++] = L'"';
    }
    return offset;
}

/*
 * Returns a newly allocated command line that joins the count arguments with spaces. The caller frees it. Returns 0
 * instead when the allocation fails.
 */
static wchar_t *buildCommandLine(int count, wchar_t **arguments) {
    size_t size = 1;
    size_t offset = 0;
    wchar_t *commandLine;
    int index;
    for (index = 0; index < count; index++) size += wcslen(arguments[index]) * 2 + 4;
    commandLine = (wchar_t *) calloc(size, sizeof(wchar_t));
    if (!commandLine) return 0;
    for (index = 0; index < count; index++) {
        if (index) commandLine[offset++] = L' ';
        offset = appendArgument(commandLine, offset, arguments[index]);
    }
    commandLine[offset] = 0;
    return commandLine;
}

/*
 * Loads library into process by running LoadLibraryW on a remote thread, and waits for that thread to finish. Returns
 * 1 when the library loaded, and -1 when LoadLibraryW failed in process. Returns 0 instead when a step in this process
 * failed, with the error in GetLastError.
 */
static int inject(HANDLE process, const wchar_t *library) {
    HMODULE kernel = GetModuleHandleW(L"kernel32.dll");
    LPTHREAD_START_ROUTINE loader;
    SIZE_T bytes = (wcslen(library) + 1) * sizeof(wchar_t);
    void *remote;
    HANDLE thread;
    DWORD loaded = 0;
    if (!kernel) return 0;
    loader = (LPTHREAD_START_ROUTINE) GetProcAddress(kernel, "LoadLibraryW");
    if (!loader) return 0;
    remote = VirtualAllocEx(process, 0, bytes, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    if (!remote) return 0;
    if (!WriteProcessMemory(process, remote, library, bytes, 0)) {
        VirtualFreeEx(process, remote, 0, MEM_RELEASE);
        return 0;
    }
    thread = CreateRemoteThread(process, 0, 0, loader, remote, 0, 0);
    if (!thread) {
        VirtualFreeEx(process, remote, 0, MEM_RELEASE);
        return 0;
    }
    WaitForSingleObject(thread, INFINITE);
    GetExitCodeThread(thread, &loaded);
    CloseHandle(thread);
    VirtualFreeEx(process, remote, 0, MEM_RELEASE);
    return loaded ? 1 : -1;
}

/*
 * Starts the executable of argv[2] suspended with the arguments that follow it, loads the library of argv[1] into it,
 * then resumes it. Returns the program's exit code once it exits, or 2 on failure.
 */
int wmain(int argc, wchar_t **argv) {
    STARTUPINFOW startup;
    PROCESS_INFORMATION started;
    wchar_t library[MAX_PATH];
    wchar_t *commandLine;
    DWORD resolved;
    DWORD exitCode = 1;
    int injected;
    if (argc < 3) {
        fwprintf(stderr, L"usage: utinject <library> <executable> [arguments...]\n");
        return 2;
    }
    resolved = GetFullPathNameW(argv[1], MAX_PATH, library, 0);
    if (!resolved || resolved >= MAX_PATH) {
        fwprintf(stderr, L"cannot resolve %ls to a path shorter than %d characters\n", argv[1], MAX_PATH);
        return 2;
    }
    if (GetFileAttributesW(library) == INVALID_FILE_ATTRIBUTES) {
        fwprintf(stderr, L"hook library not found at %ls\n", library);
        return 2;
    }
    commandLine = buildCommandLine(argc - 2, argv + 2);
    if (!commandLine) return 2;
    ZeroMemory(&startup, sizeof(startup));
    startup.cb = sizeof(startup);
    ZeroMemory(&started, sizeof(started));
    if (!CreateProcessW(0, commandLine, 0, 0, TRUE, CREATE_SUSPENDED, 0, 0, &startup, &started)) {
        fwprintf(stderr, L"cannot start %ls, error %lu\n", argv[2], GetLastError());
        free(commandLine);
        return 2;
    }
    free(commandLine);
    injected = inject(started.hProcess, library);
    if (injected != 1) {
        if (injected < 0) fwprintf(stderr, L"LoadLibraryW failed in the game process for %ls\n", library);
        else fwprintf(stderr, L"cannot inject %ls, error %lu\n", library, GetLastError());
        TerminateProcess(started.hProcess, 1);
        CloseHandle(started.hThread);
        CloseHandle(started.hProcess);
        return 2;
    }
    ResumeThread(started.hThread);
    WaitForSingleObject(started.hProcess, INFINITE);
    GetExitCodeProcess(started.hProcess, &exitCode);
    CloseHandle(started.hThread);
    CloseHandle(started.hProcess);
    return (int) exitCode;
}
