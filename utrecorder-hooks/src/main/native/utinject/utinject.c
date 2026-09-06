#include <windows.h>
#include <stdio.h>

static int needsQuotes(const wchar_t *value) {
    const wchar_t *scan;
    if (!*value) return 1;
    for (scan = value; *scan; scan++) {
        if (*scan == L' ' || *scan == L'\t' || *scan == L'"') return 1;
    }
    return 0;
}

static size_t appendArgument(wchar_t *target, size_t offset, const wchar_t *value) {
    const wchar_t *scan;
    size_t slashes = 0;
    size_t index;
    int quoted = needsQuotes(value);
    if (quoted) target[offset++] = L'"';
    for (scan = value; *scan; scan++) {
        if (*scan == L'\\') {
            slashes++;
            target[offset++] = *scan;
            continue;
        }
        if (*scan == L'"') {
            for (index = 0; index < slashes; index++) target[offset++] = L'\\';
            target[offset++] = L'\\';
        }
        slashes = 0;
        target[offset++] = *scan;
    }
    if (quoted) {
        for (index = 0; index < slashes; index++) target[offset++] = L'\\';
        target[offset++] = L'"';
    }
    return offset;
}

static wchar_t *buildCommandLine(int count, wchar_t **values) {
    size_t size = 1;
    size_t offset = 0;
    wchar_t *line;
    int index;
    for (index = 0; index < count; index++) size += wcslen(values[index]) * 2 + 4;
    line = (wchar_t *) calloc(size, sizeof(wchar_t));
    if (!line) return 0;
    for (index = 0; index < count; index++) {
        if (index) line[offset++] = L' ';
        offset = appendArgument(line, offset, values[index]);
    }
    line[offset] = 0;
    return line;
}

static int inject(HANDLE process, const wchar_t *library) {
    HMODULE kernel = GetModuleHandleW(L"kernel32.dll");
    LPTHREAD_START_ROUTINE loader;
    SIZE_T bytes = (wcslen(library) + 1) * sizeof(wchar_t);
    void *remote;
    HANDLE thread;
    DWORD result = 0;
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
    GetExitCodeThread(thread, &result);
    CloseHandle(thread);
    VirtualFreeEx(process, remote, 0, MEM_RELEASE);
    return result != 0;
}

int wmain(int argc, wchar_t **argv) {
    STARTUPINFOW startup;
    PROCESS_INFORMATION information;
    wchar_t library[MAX_PATH];
    wchar_t *line;
    DWORD status = 1;
    if (argc < 3) {
        fwprintf(stderr, L"usage: utinject <library> <executable> [arguments...]\n");
        return 2;
    }
    if (!GetFullPathNameW(argv[1], MAX_PATH, library, 0)) {
        fwprintf(stderr, L"cannot resolve %ls\n", argv[1]);
        return 2;
    }
    if (GetFileAttributesW(library) == INVALID_FILE_ATTRIBUTES) {
        fwprintf(stderr, L"hook library not found at %ls\n", library);
        return 2;
    }
    line = buildCommandLine(argc - 2, argv + 2);
    if (!line) return 2;
    ZeroMemory(&startup, sizeof(startup));
    startup.cb = sizeof(startup);
    ZeroMemory(&information, sizeof(information));
    if (!CreateProcessW(0, line, 0, 0, TRUE, CREATE_SUSPENDED, 0, 0, &startup, &information)) {
        fwprintf(stderr, L"cannot start %ls, error %lu\n", argv[2], GetLastError());
        free(line);
        return 2;
    }
    free(line);
    if (!inject(information.hProcess, library)) {
        fwprintf(stderr, L"cannot inject %ls, error %lu\n", library, GetLastError());
        TerminateProcess(information.hProcess, 1);
        CloseHandle(information.hThread);
        CloseHandle(information.hProcess);
        return 2;
    }
    ResumeThread(information.hThread);
    WaitForSingleObject(information.hProcess, INFINITE);
    GetExitCodeProcess(information.hProcess, &status);
    CloseHandle(information.hThread);
    CloseHandle(information.hProcess);
    return (int) status;
}
