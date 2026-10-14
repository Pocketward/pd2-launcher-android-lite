/*
 * close-launcher.exe: asks the official PD2 launcher to close once the game it started shows. The
 * launcher only starts Game.exe at its Play and then stays open behind the game, with its window
 * and about half a gigabyte of memory, until the player closes it. The app's session runs this,
 * in the same Wine, when the game runs and the launcher does no file work (GameActivity,
 * LauncherExit); the app ends the launcher's process only when it is still there a little later.
 *
 * It finds the processes of Game.exe and PD2Launcher.exe by their file names (a process
 * snapshot), then looks once at every visible top-level window without an owner. When a window of
 * Game.exe shows, it posts WM_CLOSE to each such window of PD2Launcher.exe, as Windows does to end
 * a program: the launcher closes as from its own close button (it saves its window's place and
 * exits). Exit code 0: asked; 2: no window of the game shows yet (nothing done); 3: the game shows,
 * but the launcher has no window. One line on standard output says what it found.
 *
 * A freestanding ARM64 Windows program with no C runtime, which the ARM64 Wine runs natively
 * (build/helpers.sh).
 */
#include <stdint.h>

typedef void *HWND;
typedef void *HANDLE;
typedef int BOOL;
typedef int32_t LONG;
typedef intptr_t LPARAM;
typedef uintptr_t WPARAM;
typedef uintptr_t ULONG_PTR;
typedef uint32_t UINT;
typedef uint32_t DWORD;
typedef uint16_t WCHAR;
typedef BOOL (*WNDENUMPROC)(HWND, LPARAM);

typedef struct {
    DWORD dwSize;
    DWORD cntUsage;
    DWORD th32ProcessID;
    ULONG_PTR th32DefaultHeapID;
    DWORD th32ModuleID;
    DWORD cntThreads;
    DWORD th32ParentProcessID;
    LONG pcPriClassBase;
    DWORD dwFlags;
    WCHAR szExeFile[260];
} PROCESSENTRY32W;

__declspec(dllimport) BOOL EnumWindows(WNDENUMPROC proc, LPARAM param);
__declspec(dllimport) BOOL IsWindowVisible(HWND window);
__declspec(dllimport) HWND GetWindow(HWND window, UINT command);
__declspec(dllimport) DWORD GetWindowThreadProcessId(HWND window, DWORD *process);
__declspec(dllimport) BOOL PostMessageW(HWND window, UINT message, WPARAM wparam, LPARAM lparam);
__declspec(dllimport) HANDLE CreateToolhelp32Snapshot(DWORD flags, DWORD process);
__declspec(dllimport) BOOL Process32FirstW(HANDLE snapshot, PROCESSENTRY32W *entry);
__declspec(dllimport) BOOL Process32NextW(HANDLE snapshot, PROCESSENTRY32W *entry);
__declspec(dllimport) BOOL CloseHandle(HANDLE handle);
__declspec(dllimport) HANDLE GetStdHandle(DWORD which);
__declspec(dllimport) BOOL WriteFile(HANDLE file, const void *data, DWORD size, DWORD *written, void *overlapped);
__declspec(dllimport) void ExitProcess(UINT code);

#define GW_OWNER 4
#define WM_CLOSE 0x0010
#define TH32CS_SNAPPROCESS 0x00000002
#define INVALID_HANDLE ((HANDLE)(intptr_t)-1)
#define STD_OUTPUT_HANDLE ((DWORD)-11)
#define PIDS 16

enum { OTHER, GAME, LAUNCHER };

static const WCHAR game_name[] = u"game.exe";
static const WCHAR launcher_name[] = u"pd2launcher.exe";

static DWORD game_pids[PIDS], launcher_pids[PIDS];
static int game_count, launcher_count;
static int windows, games, launchers, asked;

/* Whether the file name at the end of [path] (NUL-ended) is [name] (in lower case), in any case. */
static int file_is(const WCHAR *path, const WCHAR *name) {
    const WCHAR *tail = path;
    for (const WCHAR *p = path; *p; p++) {
        if (*p == '\\' || *p == '/') tail = p + 1;
    }
    int i = 0;
    for (; tail[i]; i++) {
        WCHAR c = tail[i];
        if (c >= 'A' && c <= 'Z') c = (WCHAR)(c - 'A' + 'a');
        if (name[i] == 0 || c != name[i]) return 0;
    }
    return name[i] == 0;
}

/* The pids of the game's and the launcher's processes, from a process snapshot. */
static void find_processes(void) {
    HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snapshot == INVALID_HANDLE) return;
    PROCESSENTRY32W entry;
    entry.dwSize = sizeof(entry);
    for (BOOL more = Process32FirstW(snapshot, &entry); more; more = Process32NextW(snapshot, &entry)) {
        entry.szExeFile[259] = 0;
        if (file_is(entry.szExeFile, game_name) && game_count < PIDS) game_pids[game_count++] = entry.th32ProcessID;
        if (file_is(entry.szExeFile, launcher_name) && launcher_count < PIDS) launcher_pids[launcher_count++] = entry.th32ProcessID;
    }
    CloseHandle(snapshot);
}

static int in(const DWORD *pids, int count, DWORD pid) {
    for (int i = 0; i < count; i++) {
        if (pids[i] == pid) return 1;
    }
    return 0;
}

/* The program of [window]'s process: GAME, LAUNCHER or OTHER. */
static int program_of(HWND window) {
    DWORD process = 0;
    GetWindowThreadProcessId(window, &process);
    if (process && in(game_pids, game_count, process)) return GAME;
    if (process && in(launcher_pids, launcher_count, process)) return LAUNCHER;
    return OTHER;
}

static int shown(HWND window) {
    return IsWindowVisible(window) && GetWindow(window, GW_OWNER) == 0;
}

static BOOL count(HWND window, LPARAM unused) {
    (void)unused;
    if (!shown(window)) return 1;
    windows++;
    int program = program_of(window);
    if (program == GAME) games++;
    if (program == LAUNCHER) launchers++;
    return 1;
}

static BOOL close_launcher(HWND window, LPARAM unused) {
    (void)unused;
    if (shown(window) && program_of(window) == LAUNCHER && PostMessageW(window, WM_CLOSE, 0, 0)) asked++;
    return 1;
}

static char *put(char *at, const char *text) {
    while (*text) *at++ = *text++;
    return at;
}

static char *put_number(char *at, int value) {
    char digits[12];
    int n = 0;
    do {
        digits[n++] = (char)('0' + value % 10);
        value /= 10;
    } while (value && n < 11);
    while (n) *at++ = digits[--n];
    return at;
}

/* One line on standard output: what it found and did. */
static void report(void) {
    char line[160];
    char *at = put(line, "close-launcher: processes game ");
    at = put_number(at, game_count);
    at = put(at, ", launcher ");
    at = put_number(at, launcher_count);
    at = put(at, "; shown windows ");
    at = put_number(at, windows);
    at = put(at, ", the game's ");
    at = put_number(at, games);
    at = put(at, ", the launcher's ");
    at = put_number(at, launchers);
    at = put(at, "; asked ");
    at = put_number(at, asked);
    at = put(at, "\n");
    DWORD written;
    WriteFile(GetStdHandle(STD_OUTPUT_HANDLE), line, (DWORD)(at - line), &written, 0);
}

void start(void) {
    find_processes();
    EnumWindows(count, 0);
    if (games && launchers) EnumWindows(close_launcher, 0);
    report();
    ExitProcess(!games ? 2 : asked ? 0 : 3);
}
