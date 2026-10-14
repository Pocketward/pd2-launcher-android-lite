/*
 * keep-on-screen.exe: keeps the official PD2 launcher's window on the X screen, as a window
 * manager would. The launcher puts its window where it was last (its AppData/launcherSettings.json,
 * saved on a PC or on a larger X screen) when that place starts on the screen, so part of the
 * window, its Play button too, can lie off a smaller X screen. Xvfb has no window manager, and a
 * move made on the X side alone is undone by Wine (the window's program owns its place).
 *
 * The app's session runs this beside the launcher, in the same Wine. Four times a second, each
 * visible, not minimized top-level window without an owner and not a tool window (so not a menu,
 * a tooltip or a drop-down list) that fits the screen but lies partly off it is moved to the middle
 * of the screen along each side it crosses, with SetWindowPos: the program learns its new place
 * (and the launcher saves it). A window wholly off the screen stays where it is (a parked one), and
 * each window is moved at most MAX_MOVES times, so a program that puts it back is not fought. The
 * session's end ends it.
 *
 * A freestanding ARM64 Windows program with no C runtime, which the ARM64 Wine runs natively
 * (build/helpers.sh).
 */
#include <stdint.h>

typedef void *HWND;
typedef int BOOL;
typedef int32_t LONG;
typedef intptr_t LPARAM;
typedef uint32_t UINT;
typedef uint32_t DWORD;
typedef struct {
    LONG left, top, right, bottom;
} RECT;
typedef BOOL (*WNDENUMPROC)(HWND, LPARAM);

__declspec(dllimport) BOOL EnumWindows(WNDENUMPROC proc, LPARAM param);
__declspec(dllimport) BOOL IsWindowVisible(HWND window);
__declspec(dllimport) BOOL IsIconic(HWND window);
__declspec(dllimport) HWND GetWindow(HWND window, UINT command);
__declspec(dllimport) LONG GetWindowLongW(HWND window, int index);
__declspec(dllimport) BOOL GetWindowRect(HWND window, RECT *rect);
__declspec(dllimport) int GetSystemMetrics(int index);
__declspec(dllimport) BOOL SetWindowPos(HWND window, HWND after, int x, int y, int width, int height, UINT flags);
__declspec(dllimport) void Sleep(DWORD milliseconds);

#define GW_OWNER 4
#define GWL_EXSTYLE (-20)
#define WS_EX_TOOLWINDOW 0x00000080
#define SM_CXSCREEN 0
#define SM_CYSCREEN 1
#define SWP_NOSIZE 0x0001
#define SWP_NOZORDER 0x0004
#define SWP_NOACTIVATE 0x0010

#define MAX_MOVES 3
#define SLOTS 32
#define PERIOD_MS 250

static HWND moved[SLOTS];
static int moves[SLOTS];
static int next_slot;

/* The new place along one side: [at], or the middle when a [size] window at [at] crosses the
 * [screen]'s edge. */
static int inside_place(int at, int size, int screen) {
    return at < 0 || at + size > screen ? (screen - size) / 2 : at;
}

/* How often [window] was moved, its slot made when [make] (the oldest goes). */
static int *move_count(HWND window, int make) {
    for (int i = 0; i < SLOTS; i++) {
        if (moved[i] == window) return &moves[i];
    }
    if (!make) return 0;
    int slot = next_slot;
    next_slot = (next_slot + 1) % SLOTS;
    moved[slot] = window;
    moves[slot] = 0;
    return &moves[slot];
}

static BOOL keep(HWND window, LPARAM unused) {
    (void)unused;
    if (!IsWindowVisible(window) || IsIconic(window) || GetWindow(window, GW_OWNER) != 0 ||
        (GetWindowLongW(window, GWL_EXSTYLE) & WS_EX_TOOLWINDOW)) {
        return 1;
    }
    RECT r;
    if (!GetWindowRect(window, &r)) return 1;
    int width = GetSystemMetrics(SM_CXSCREEN), height = GetSystemMetrics(SM_CYSCREEN);
    int w = r.right - r.left, h = r.bottom - r.top;
    if (w <= 1 || h <= 1 || w > width || h > height) return 1;
    /* Wholly off the screen: parked, left as it is. */
    if (r.right <= 0 || r.bottom <= 0 || r.left >= width || r.top >= height) return 1;
    int x = inside_place(r.left, w, width), y = inside_place(r.top, h, height);
    if (x == r.left && y == r.top) return 1;
    int *count = move_count(window, 1);
    if (*count >= MAX_MOVES) return 1;
    (*count)++;
    SetWindowPos(window, 0, x, y, 0, 0, SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE);
    return 1;
}

void start(void) {
    for (;;) {
        EnumWindows(keep, 0);
        Sleep(PERIOD_MS);
    }
}
