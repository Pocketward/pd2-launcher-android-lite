// Native side of PD2 Launcher Lite (libpd2lite.so):
// - the process spawner, memfd and socket pair of PD2 Launcher for Android (its
//   app/src/main/cpp/spawner.c), unchanged but for the class it reports to;
// - a minimal frame copy: Xvfb's framebuffer (an XWD file in a memfd: header, then 32-bit BGRX)
//   copied into a Surface whenever it changed, scaled by the compositor. It counts the frames it
//   posted and, when Mesa's present counter (userland Mesa patch 0002, PD2_PRESENT_COUNTER) is
//   given, the game's presents, for the fps readout.
#define _GNU_SOURCE
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <arm_neon.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <pthread.h>
#include <signal.h>
#include <spawn.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define TAG "pd2-lite"
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static JavaVM *vm;
static jclass native_class;
static jmethodID on_exit_method;

JNIEXPORT jint JNI_OnLoad(JavaVM *loaded_vm, void *reserved) {
    (void)reserved;
    vm = loaded_vm;
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass local = (*env)->FindClass(env, "com/pd2launcher/box64/Native");
    if (local == NULL) return JNI_ERR;
    native_class = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    on_exit_method = (*env)->GetStaticMethodID(env, native_class, "onExit", "(IZI)V");
    if (on_exit_method == NULL) return JNI_ERR;
    return JNI_VERSION_1_6;
}

// ---- Processes ------------------------------------------------------------------------------

static void *reap(void *arg) {
    pid_t pid = (pid_t)(intptr_t)arg;
    int status = 0;
    pid_t waited;
    do {
        waited = waitpid(pid, &status, 0);
    } while (waited < 0 && errno == EINTR);
    jboolean exited = JNI_TRUE;
    jint value = -1;
    if (waited == pid) {
        if (WIFEXITED(status)) {
            value = WEXITSTATUS(status);
        } else if (WIFSIGNALED(status)) {
            exited = JNI_FALSE;
            value = WTERMSIG(status);
        }
    }
    JNIEnv *env;
    JavaVMAttachArgs args = {JNI_VERSION_1_6, "box64-reaper", NULL};
    if ((*vm)->AttachCurrentThread(vm, &env, &args) == JNI_OK) {
        (*env)->CallStaticVoidMethod(env, native_class, on_exit_method, (jint)pid, exited, value);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        (*vm)->DetachCurrentThread(vm);
    }
    return NULL;
}

static void mark_cloexec(void) {
    DIR *dir = opendir("/proc/self/fd");
    if (dir == NULL) return;
    int own = dirfd(dir);
    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        char *end;
        long fd = strtol(entry->d_name, &end, 10);
        if (*end != '\0' || end == entry->d_name || fd <= 2 || fd == own) continue;
        int flags = fcntl((int)fd, F_GETFD);
        if (flags >= 0 && !(flags & FD_CLOEXEC)) fcntl((int)fd, F_SETFD, flags | FD_CLOEXEC);
    }
    closedir(dir);
}

static char **copy_strings(JNIEnv *env, jobjectArray array) {
    jsize count = (*env)->GetArrayLength(env, array);
    char **strings = calloc((size_t)count + 1, sizeof(char *));
    if (strings == NULL) return NULL;
    for (jsize i = 0; i < count; i++) {
        jstring string = (jstring)(*env)->GetObjectArrayElement(env, array, i);
        const char *chars = (*env)->GetStringUTFChars(env, string, NULL);
        strings[i] = chars ? strdup(chars) : NULL;
        if (chars) (*env)->ReleaseStringUTFChars(env, string, chars);
        (*env)->DeleteLocalRef(env, string);
        if (strings[i] == NULL) {
            for (jsize j = 0; j < i; j++) free(strings[j]);
            free(strings);
            return NULL;
        }
    }
    return strings;
}

static void free_strings(char **strings) {
    if (strings == NULL) return;
    for (char **s = strings; *s != NULL; s++) free(*s);
    free(strings);
}

// Returns the child's pid, or -errno. [fd3]: a descriptor the child gets as its descriptor 3, or -1.
JNIEXPORT jint JNICALL Java_com_pd2launcher_box64_Native_spawn(JNIEnv *env, jclass clazz, jstring path,
                                                              jobjectArray argv, jobjectArray envp,
                                                              jint stdin_fd, jint output_fd, jint fd3) {
    (void)clazz;
    int result = 0;
    pid_t pid = 0;
    char **child_argv = copy_strings(env, argv);
    char **child_envp = child_argv != NULL ? copy_strings(env, envp) : NULL;
    const char *child_path = child_envp != NULL ? (*env)->GetStringUTFChars(env, path, NULL) : NULL;
    if (child_argv == NULL || child_envp == NULL || child_path == NULL) {
        result = -ENOMEM;
        goto out;
    }
    int fd3_copy = -1;
    if (fd3 == 3) {
        fd3_copy = fcntl(fd3, F_DUPFD_CLOEXEC, 4);
        if (fd3_copy < 0) {
            result = -errno;
            goto out;
        }
    }
    posix_spawn_file_actions_t actions;
    posix_spawnattr_t attr;
    posix_spawn_file_actions_init(&actions);
    posix_spawnattr_init(&attr);
    posix_spawn_file_actions_adddup2(&actions, stdin_fd, 0);
    posix_spawn_file_actions_adddup2(&actions, output_fd, 1);
    posix_spawn_file_actions_adddup2(&actions, output_fd, 2);
    if (fd3 >= 0) posix_spawn_file_actions_adddup2(&actions, fd3_copy >= 0 ? fd3_copy : fd3, 3);
    sigset_t empty, defaults;
    sigemptyset(&empty);
    sigemptyset(&defaults);
    for (int s = 1; s < 32; s++) {
        if (s != SIGKILL && s != SIGSTOP) sigaddset(&defaults, s);
    }
    posix_spawnattr_setsigmask(&attr, &empty);
    posix_spawnattr_setsigdefault(&attr, &defaults);
    posix_spawnattr_setflags(&attr, POSIX_SPAWN_SETSID | POSIX_SPAWN_SETSIGMASK | POSIX_SPAWN_SETSIGDEF);
    mark_cloexec();
    mode_t umask_before = umask(077);
    result = posix_spawn(&pid, child_path, &actions, &attr, child_argv, child_envp);
    umask(umask_before);
    posix_spawn_file_actions_destroy(&actions);
    if (fd3_copy >= 0) close(fd3_copy);
    posix_spawnattr_destroy(&attr);
    if (result != 0) {
        result = -result;
        goto out;
    }
    pthread_t thread;
    pthread_attr_t thread_attr;
    int thread_result = pthread_attr_init(&thread_attr);
    if (thread_result == 0) {
        pthread_attr_setdetachstate(&thread_attr, PTHREAD_CREATE_DETACHED);
        thread_result = pthread_create(&thread, &thread_attr, reap, (void *)(intptr_t)pid);
        pthread_attr_destroy(&thread_attr);
    }
    if (thread_result != 0) {
        kill(pid, SIGKILL);
        while (waitpid(pid, NULL, 0) < 0 && errno == EINTR) {
        }
        result = -thread_result;
        goto out;
    }
    result = pid;
out:
    if (child_path) (*env)->ReleaseStringUTFChars(env, path, child_path);
    free_strings(child_argv);
    free_strings(child_envp);
    return result;
}

// A connected AF_UNIX stream pair, both ends close-on-exec: [app end, child end].
JNIEXPORT jintArray JNICALL Java_com_pd2launcher_box64_Native_socketPair(JNIEnv *env, jclass clazz) {
    (void)clazz;
    int fds[2];
    if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, fds) != 0) return NULL;
    jintArray result = (*env)->NewIntArray(env, 2);
    if (result == NULL) {
        close(fds[0]);
        close(fds[1]);
        return NULL;
    }
    jint values[2] = {fds[0], fds[1]};
    (*env)->SetIntArrayRegion(env, result, 0, 2, values);
    return result;
}

// A new memfd, close-on-exec, that can grow but never shrink. Returns the descriptor or -errno.
JNIEXPORT jint JNICALL Java_com_pd2launcher_box64_Native_memfd(JNIEnv *env, jclass clazz, jstring name) {
    (void)clazz;
    const char *chars = (*env)->GetStringUTFChars(env, name, NULL);
    if (chars == NULL) return -ENOMEM;
    int fd = memfd_create(chars, MFD_CLOEXEC | MFD_ALLOW_SEALING);
    int error = errno;
    (*env)->ReleaseStringUTFChars(env, name, chars);
    if (fd >= 0 && fcntl(fd, F_ADD_SEALS, F_SEAL_SHRINK) != 0) {
        error = errno;
        close(fd);
        fd = -1;
    }
    return fd >= 0 ? fd : -error;
}

// Sends a signal to a whole process group (the children start their own sessions).
JNIEXPORT jint JNICALL Java_com_pd2launcher_box64_Native_killGroup(JNIEnv *env, jclass clazz, jint pgid, jint sig) {
    (void)env;
    (void)clazz;
    return kill(-pgid, sig) == 0 ? 0 : -errno;
}

// ---- Frame copy -----------------------------------------------------------------------------

struct copy {
    ANativeWindow *window;
    int fd;
    uint8_t *map;
    size_t map_len;
    int width, height, stride, offset;
    pthread_t thread;
    atomic_bool stop;
    _Alignas(8) atomic_uint_fast64_t posted;
    uint32_t *presents; // Mesa's present counter (first word), or NULL
    uint64_t last_hash;
};

static int64_t now_ns(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (int64_t)t.tv_sec * 1000000000LL + t.tv_nsec;
}

static uint32_t field(const uint8_t *h, int index, int big) {
    uint32_t v;
    memcpy(&v, h + index * 4, 4);
    return big ? __builtin_bswap32(v) : v;
}

// Maps the framebuffer once Xvfb has written its header; 0 when ready.
static int map_framebuffer(struct copy *c) {
    struct stat st;
    if (fstat(c->fd, &st) != 0 || st.st_size < 100) return -1;
    uint8_t *map = mmap(NULL, (size_t)st.st_size, PROT_READ, MAP_SHARED, c->fd, 0);
    if (map == MAP_FAILED) return -1;
    int big = field(map, 1, 1) == 7;
    if (!big && field(map, 1, 0) != 7) {
        munmap(map, (size_t)st.st_size);
        return -1;
    }
    uint32_t header = field(map, 0, big), width = field(map, 4, big), height = field(map, 5, big);
    uint32_t bpp = field(map, 11, big), bpl = field(map, 12, big), ncolors = field(map, 19, big);
    uint64_t offset = header + (uint64_t)ncolors * 12;
    if (bpp != 32 || width == 0 || height == 0 || width > 16384 || height > 16384 || bpl < width * 4 ||
        offset + (uint64_t)bpl * height > (uint64_t)st.st_size) {
        munmap(map, (size_t)st.st_size);
        return -1;
    }
    c->map = map;
    c->map_len = (size_t)st.st_size;
    c->width = (int)width;
    c->height = (int)height;
    c->stride = (int)(bpl / 4);
    c->offset = (int)offset;
    LOG("framebuffer %dx%d stride %d offset %d", c->width, c->height, c->stride, c->offset);
    return 0;
}

static uint64_t hash_frame(const struct copy *c) {
    const uint32_t *px = (const uint32_t *)(c->map + c->offset);
    uint64_t h = 1469598103934665603ULL;
    // Every 4th row in full: a change of a few pixels on other rows can be missed for one frame,
    // and the next change shows it.
    for (int y = 0; y < c->height; y += 4) {
        const uint64_t *row = (const uint64_t *)(px + (size_t)y * c->stride);
        uint64_t a = 0;
        for (int x = 0; x < c->width / 2; x++) a = a * 31 + row[x];
        h = (h ^ a) * 1099511628211ULL;
    }
    return h;
}

static void post(struct copy *c) {
    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(c->window, &buffer, NULL) != 0) return;
    const uint32_t *src = (const uint32_t *)(c->map + c->offset);
    uint32_t *dst = buffer.bits;
    int w = c->width < buffer.width ? c->width : buffer.width;
    int h = c->height < buffer.height ? c->height : buffer.height;
    static const uint8_t order[16] = {2, 1, 0, 255, 6, 5, 4, 255, 10, 9, 8, 255, 14, 13, 12, 255};
    uint8x16_t tbl = vld1q_u8(order);
    uint32x4_t alpha = vdupq_n_u32(0xFF000000u);
    for (int y = 0; y < h; y++) {
        const uint32_t *s = src + (size_t)y * c->stride;
        uint32_t *d = dst + (size_t)y * buffer.stride;
        int x = 0;
        for (; x + 4 <= w; x += 4) {
            uint8x16_t p = vqtbl1q_u8(vreinterpretq_u8_u32(vld1q_u32(s + x)), tbl);
            vst1q_u32(d + x, vorrq_u32(vreinterpretq_u32_u8(p), alpha));
        }
        for (; x < w; x++) {
            uint32_t p = s[x];
            d[x] = 0xFF000000u | (p & 0xFF00u) | ((p >> 16) & 0xFFu) | ((p & 0xFFu) << 16);
        }
    }
    ANativeWindow_unlockAndPost(c->window);
    atomic_fetch_add(&c->posted, 1);
}

static void *copy_thread(void *arg) {
    struct copy *c = arg;
    while (!atomic_load(&c->stop) && map_framebuffer(c) != 0) usleep(20000);
    if (atomic_load(&c->stop)) return NULL;
    ANativeWindow_setBuffersGeometry(c->window, c->width, c->height, WINDOW_FORMAT_RGBX_8888);
    int64_t next = now_ns();
    while (!atomic_load(&c->stop)) {
        uint64_t h = hash_frame(c);
        if (h != c->last_hash) {
            c->last_hash = h;
            post(c);
        }
        // About 120 checks a second: a 60 Hz game's frames are seen within half a frame.
        next += 8333333;
        int64_t wait = next - now_ns();
        if (wait > 0) {
            struct timespec t = {0, (long)wait};
            nanosleep(&t, NULL);
        } else {
            next = now_ns();
        }
    }
    return NULL;
}

JNIEXPORT jlong JNICALL Java_com_pd2launcher_box64_Native_startCopy(JNIEnv *env, jclass clazz, jobject surface,
                                                                   jint fd, jstring counter_path) {
    (void)clazz;
    struct copy *c = calloc(1, sizeof *c);
    if (c == NULL) return 0;
    c->window = ANativeWindow_fromSurface(env, surface);
    c->fd = dup(fd);
    if (counter_path != NULL) {
        const char *path = (*env)->GetStringUTFChars(env, counter_path, NULL);
        int cfd = path ? open(path, O_RDONLY | O_CLOEXEC) : -1;
        if (path) (*env)->ReleaseStringUTFChars(env, counter_path, path);
        if (cfd >= 0) {
            void *m = mmap(NULL, 4096, PROT_READ, MAP_SHARED, cfd, 0);
            if (m != MAP_FAILED) c->presents = m;
            close(cfd);
        }
    }
    if (c->window == NULL || c->fd < 0 || pthread_create(&c->thread, NULL, copy_thread, c) != 0) {
        if (c->window) ANativeWindow_release(c->window);
        if (c->fd >= 0) close(c->fd);
        free(c);
        return 0;
    }
    return (jlong)(intptr_t)c;
}

// [frames posted, game presents or -1].
JNIEXPORT jlongArray JNICALL Java_com_pd2launcher_box64_Native_copyCounts(JNIEnv *env, jclass clazz, jlong handle) {
    (void)clazz;
    struct copy *c = (struct copy *)(intptr_t)handle;
    jlong values[2] = {0, -1};
    if (c != NULL) {
        values[0] = (jlong)atomic_load(&c->posted);
        if (c->presents) values[1] = (jlong)__atomic_load_n(c->presents, __ATOMIC_RELAXED);
    }
    jlongArray result = (*env)->NewLongArray(env, 2);
    if (result) (*env)->SetLongArrayRegion(env, result, 0, 2, values);
    return result;
}

JNIEXPORT void JNICALL Java_com_pd2launcher_box64_Native_stopCopy(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    struct copy *c = (struct copy *)(intptr_t)handle;
    if (c == NULL) return;
    atomic_store(&c->stop, true);
    pthread_join(c->thread, NULL);
    if (c->map) munmap(c->map, c->map_len);
    if (c->presents) munmap(c->presents, 4096);
    ANativeWindow_release(c->window);
    close(c->fd);
    free(c);
}

// A full memory barrier for the gamepad file's seqlock (input/Gamepad.kt): the game's
// xinput1_4.dll reads the mapped file from another process, and the app's minimum API level
// has no VarHandle fences (as the main app's input_fence.c).
JNIEXPORT void JNICALL Java_com_pd2launcher_box64_input_GamepadFile_nativeFence(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    __atomic_thread_fence(__ATOMIC_SEQ_CST);
}
