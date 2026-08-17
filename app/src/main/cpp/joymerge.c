/*
 * JoyMerge Quest - native evdev / uinput bridge.
 *
 * Everything here is a thin, honest wrapper around the Linux input stack:
 *   - opening and probing /dev/input/event* (read the two Joy-Cons directly,
 *     which is the only way to keep receiving them once our app loses focus)
 *   - opening /dev/uinput and publishing a single merged gamepad
 *
 * Every entry point reports the real errno back to Kotlin. Nothing here
 * pretends to succeed: if the kernel or SELinux says no, the negative errno
 * travels all the way up to the Diagnostics screen.
 *
 * This code is expected to run inside the Shizuku user-service process
 * (uid 2000 / shell). Running it inside the normal app process will almost
 * always fail with EACCES, and that is exactly what we want to surface.
 */

#include <jni.h>
#include <string.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <poll.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <android/log.h>

#include <linux/input.h>
#include <linux/uinput.h>

#define LOG_TAG "JoyMergeNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define JM_EXPORT __attribute__((visibility("default")))

/* linux/uinput.h in some NDK versions predates UI_GET_SYSNAME. */
#ifndef UI_GET_SYSNAME
#define UI_GET_SYSNAME(len) _IOC(_IOC_READ, UINPUT_IOCTL_BASE, 44, len)
#endif

#define BITS_PER_LONG (sizeof(long) * 8)
#define NBITS(x) ((((x) - 1) / BITS_PER_LONG) + 1)
#define TEST_BIT(bit, array) ((array[(bit) / BITS_PER_LONG] >> ((bit) % BITS_PER_LONG)) & 1)

static jint negative_errno(void) {
    int e = errno;
    if (e <= 0) {
        e = EIO;
    }
    return (jint) -e;
}

/* ------------------------------------------------------------------ */
/* Generic fd / filesystem helpers                                     */
/* ------------------------------------------------------------------ */

JM_EXPORT JNIEXPORT jint JNICALL
Java_com_joymerge_quest_nativebridge_NativeBridge_openFd(
        JNIEnv *env, jclass clazz, jstring jpath, jboolean writable, jboolean nonBlocking) {
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (path == NULL) {
        return -ENOMEM;
    }
    int flags = writable ? O_RDWR : O_RDONLY;
    flags |= O_CLOEXEC;
    if (nonBlocking) {
        flags |= O_NONBLOCK;
    }
    errno = 0;
    int fd = open(path, flags);
    jint result = (fd < 0) ? negative_errno() : (jint) fd;
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return result;
}

JM_EXPORT JNIEXPORT void JNICALL
Java_com_joymerge_quest_nativebridge_NativeBridge_closeFd(JNIEnv *env, jclass clazz, jint fd) {
    if (fd >= 0) {
        close(fd);
    }
}

JM_EXPORT JNIEXPORT jstring JNICALL
Java_com_joymerge_quest_nativebridge_NativeBridge_errnoMessage(JNIEnv *env, jclass clazz, jint err) {
    int e = err < 0 ? -err : err;
    const char *msg = strerror(e);
    return (*env)->NewStringUTF(env, msg == NULL ? "unknown error" : msg);
}

/*
 * Returns [ok, mode, uid, gid, errno, readable, writable].
 * ok is 1 when stat() succeeded. readable/writable come from access(2), which
 * answers the question we actually care about: can *this* uid open it.
 */
JM_EXPORT JNIEXPORT jlongArray JNICALL
Java_com_joymerge_quest_nativebridge_NativeBridge_statPath(JNIEnv *env, jclass clazz, jstring jpath) {
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (path == NULL) {
        return NULL;
    }
    jlong values[7];
    memset(values, 0, sizeof(values));

    struct stat st;
    errno = 0;
    if (stat(path, &st) == 0) {
        values[0] = 1;
        values[1] = (jlong) st.st_mode;
        values[2] = (jlong) st.st_uid;
        values[3] = (jlong) st.st_gid;
        values[4] = 0;
    } else {
        values[0] = 0;
        values[4] = (jlong) errno;
    }
    values[5] = (access(path, R_OK) == 0) ? 1 : 0;
    values[6] = (access(path, W_OK) == 0) ? 1 : 0;

    (*env)->ReleaseStringUTFChars(env, jpath, path);

    jlongArray out = (*env)->NewLongArray(env, 7);
    if (out == NULL) {
        return NULL;
    }
    (*env)->SetLongArrayRegion(env, out, 0, 7, values);
    return out;
}

JM_EXPORT JNIEXPORT jint JNICALL
Java_com_joymerge_quest_nativebridge_NativeBridge_getUid(JNIEnv *env, jclass clazz) {
    return (jint) getuid();
}

JM_EXPORT JNIEXPORT jint JNICALL
Java_com_joymerge_quest_nativebridge_NativeBridge_getEuid(JNIEnv *env, jclass clazz) {
    return (jint) geteuid();
}

JM_EXPORT JNIEXPORT jintArray JNICALL
Java_com_joymerge_quest_nativebridge_NativeBridge_getGroups(JNIEnv *env, jclass clazz) {
    int count = getgroups(0, NULL);
    if (count < 0) {
        count = 0;
    }
    gid_t *groups = NULL;
    if (count > 0) {
        groups = (gid_t *) calloc((size_t) count, sizeof(gid_t));
        if (groups == NULL) {
            return NULL;
        }
        count = getgroups(count, groups);
        if (count < 0) {
            free(groups);
            count = 0;
            groups = NULL;
        }
    }
    jintArray out = (*env)->NewIntArray(env, count);
    if (out != NULL && count > 0) {
        jint *tmp = (jint *) calloc((size_t) count, sizeof(jint));
        if (tmp != NULL) {
            for (int i = 0; i < count; i++) {
                tmp[i] = (jint) groups[i];
            }
            (*env)->SetIntArrayRegion(env, out, 0, count, tmp);
            free(tmp);
        }
    }
    free(groups);
    return out;
}

/* ------------------------------------------------------------------ */
/* evdev probing                                                       */
/* ------------------------------------------------------------------ */

JM_EXPORT JNIEXPORT jstring JNICALL
Java_com_joymerge_quest_nativebridge_NativeBridge_evdevName(JNIEnv *env, jclass clazz, jint fd) {
    char name[256];
    memset(name, 0, sizeof(name));
    if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) < 0) {
        return NULL;
    }
    return (*env)->NewStringUTF(env, name);
}

JM_EXPORT JNIEXPORT jstring JNICALL
Java_com_joymerge_quest_nativebridge_NativeBridge_evdevUniq(JNIEnv *env, jclass clazz, jint fd) {
    char uniq[128];
    memset(uniq, 0, sizeof(uniq));
    if (ioctl(fd, EVIOCGUNIQ(sizeof(uniq) - 1), uniq) < 0) {
        return NULL;
    }
    return (*env)->NewStringUTF(env, uniq);
}

/* Returns [bustype, vendor, product, version]. */
JM_EXPORT JNIEXPORT jintArray JNICALL
Java_com_joymerge_quest_nativebridge_NativeBridge_evdevId(JNIEnv *env, jclass clazz, jint fd) {
    struct input_id id;
    memset(&id, 0, sizeof(id));
    if (ioctl(fd, EVIOCGID, &id) < 0) {
        return NULL;
    }
    jint values[4] = {id.bustype, id.vendor, id.product, id.version};
    jintArray out = (*env)->NewIntArray(env, 4);
    if (out == NULL) {
        return NULL;
    }
    (*env)->SetIntArrayRegion(env, out, 0, 4, values);
    return out;
}

/* Lists every supported code for the given EV_* type. */
JM_EXPORT JNIEXPORT jintArray JNICALL
Java_com_joymerge_quest_nativebridge_NativeBridge_evdevCodes(
        JNIEnv *env, jclass clazz, jint fd, jint evType, jint maxCode) {
    if (maxCode <= 0 || maxCode > 0x800) {
        maxCode = KEY_MAX;
    }
    size_t words = NBITS((size_t) maxCode + 1);
    unsigned long *bits = (unsigned long *) calloc(words, sizeof(unsigned long));
    if (bits == NULL) {
        return NULL;
    }
    if (ioctl(fd, EVIOCGBIT(evType, (int) (words * sizeof(unsigned long))), bits) < 0) {
        free(bits);
        return NULL;
    }
    jint *found = (jint *) calloc((size_t) maxCode + 1, sizeof(jint));
    if (found == NULL) {
        free(bits);
        return NULL;
    }
    int count = 0;
    for (int code = 0; code <= maxCode; code++) {
        if (TEST_BIT(code, bits)) {
            found[count++] = code;
        }
    }
    free(bits);

    jintArray out = (*env)->NewIntArray(env, count);
    if (out != NULL && count > 0) {
        (*env)->SetIntArrayRegion(env, out, 0, count, found);
    }
    free(found);
    return out;
}

/* Returns [value, min, max, fuzz, flat, resolution] for one ABS axis. */
JM_EXPORT JNIEXPORT jintArray JNICALL
Java_com_joymerge_quest_nativebridge_NativeBridge_evdevAbsInfo(
        JNIEnv *env, jclass clazz, jint fd, jint axis) {
    struct input_absinfo info;
    memset(&info, 0, sizeof(info));
    if (ioctl(fd, EVIOCGABS(axis), &info) < 0) {
        return NULL;
    }
    jint values[6] = {info.value, info.minimum, info.maximum, info.fuzz, info.flat, info.resolution};
    jintArray out = (*env)->NewIntArray(env, 6);
    if (out == NULL) {
        return NULL;
    }
    (*env)->SetIntArrayRegion(env, out, 0, 6, values);
    return out;
}

/*
 * EVIOCGRAB takes the device away from every other reader, including Android's
 * own InputReader. That is how we stop the Quest from also seeing the two
 * Joy-Cons as separate controllers while we are merging them.
 */
JM_EXPORT JNIEXPORT jint JNICALL
Java_com_joymerge_quest_nativebridge_NativeBridge_evdevGrab(
        JNIEnv *env, jclass clazz, jint fd, jboolean grab) {
    errno = 0;
    if (ioctl(fd, EVIOCGRAB, grab ? 1 : 0) < 0) {
        return negative_errno();
    }
    return 0;
}

/*
 * Blocking-with-timeout read. Writes flat [type, code, value] triples into
 * `out` and returns how many ints were written, 0 on timeout, or -errno.
 */
JM_EXPORT JNIEXPORT jint JNICALL
Java_com_joymerge_quest_nativebridge_NativeBridge_evdevRead(
        JNIEnv *env, jclass clazz, jint fd, jintArray out, jint timeoutMs) {
    jsize capacity = (*env)->GetArrayLength(env, out);
    if (capacity < 3) {
        return -EINVAL;
    }
    int maxEvents = capacity / 3;

    struct pollfd pfd;
    pfd.fd = fd;
    pfd.events = POLLIN;
    pfd.revents = 0;

    errno = 0;
    int ready = poll(&pfd, 1, timeoutMs);
    if (ready < 0) {
        if (errno == EINTR) {
            return 0;
        }
        return negative_errno();
    }
    if (ready == 0) {
        return 0;
    }
    if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) {
        /* Device unplugged / Bluetooth link dropped. */
        return -ENODEV;
    }

    struct input_event events[64];
    if (maxEvents > 64) {
        maxEvents = 64;
    }
    errno = 0;
    ssize_t bytes = read(fd, events, sizeof(struct input_event) * (size_t) maxEvents);
    if (bytes < 0) {
        if (errno == EAGAIN || errno == EINTR) {
            return 0;
        }
        return negative_errno();
    }
    if (bytes == 0) {
        return 0;
    }

    int count = (int) (bytes / (ssize_t) sizeof(struct input_event));
    jint *flat = (jint *) calloc((size_t) count * 3, sizeof(jint));
    if (flat == NULL) {
        return -ENOMEM;
    }
    for (int i = 0; i < count; i++) {
        flat[i * 3] = events[i].type;
        flat[i * 3 + 1] = events[i].code;
        flat[i * 3 + 2] = events[i].value;
    }
    (*env)->SetIntArrayRegion(env, out, 0, count * 3, flat);
    free(flat);
    return count * 3;
}

/* ------------------------------------------------------------------ */
/* uinput                                                              */
/* ------------------------------------------------------------------ */

/*
 * Creates the merged virtual gamepad.
 *
 * keyCodes  : EV_KEY codes to advertise (BTN_A, BTN_TL, ...)
 * absCodes  : EV_ABS codes to advertise (ABS_X, ABS_Z, ABS_HAT0X, ...)
 * absRanges : flat [min, max, fuzz, flat] per entry of absCodes
 *
 * Tries the modern UI_DEV_SETUP/UI_ABS_SETUP path first and falls back to the
 * legacy write(uinput_user_dev) path used by older kernels. Returns 0 on
 * success or -errno on the first failing step.
 */
JM_EXPORT JNIEXPORT jint JNICALL
Java_com_joymerge_quest_nativebridge_NativeBridge_uinputCreate(
        JNIEnv *env, jclass clazz, jint fd, jstring jname,
        jint busType, jint vendor, jint product, jint version,
        jintArray jkeyCodes, jintArray jabsCodes, jintArray jabsRanges) {

    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    if (name == NULL) {
        return -ENOMEM;
    }

    jsize keyCount = jkeyCodes == NULL ? 0 : (*env)->GetArrayLength(env, jkeyCodes);
    jsize absCount = jabsCodes == NULL ? 0 : (*env)->GetArrayLength(env, jabsCodes);
    jsize rangeCount = jabsRanges == NULL ? 0 : (*env)->GetArrayLength(env, jabsRanges);
    if (rangeCount != absCount * 4) {
        (*env)->ReleaseStringUTFChars(env, jname, name);
        return -EINVAL;
    }

    jint *keyCodes = keyCount > 0 ? (*env)->GetIntArrayElements(env, jkeyCodes, NULL) : NULL;
    jint *absCodes = absCount > 0 ? (*env)->GetIntArrayElements(env, jabsCodes, NULL) : NULL;
    jint *absRanges = rangeCount > 0 ? (*env)->GetIntArrayElements(env, jabsRanges, NULL) : NULL;

    jint result = 0;

#define FAIL_IF(cond)                       \
    do {                                    \
        if (cond) {                         \
            result = negative_errno();      \
            goto cleanup;                   \
        }                                   \
    } while (0)

    errno = 0;
    FAIL_IF(ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0);
    FAIL_IF(ioctl(fd, UI_SET_EVBIT, EV_SYN) < 0);
    if (absCount > 0) {
        FAIL_IF(ioctl(fd, UI_SET_EVBIT, EV_ABS) < 0);
    }

    for (jsize i = 0; i < keyCount; i++) {
        FAIL_IF(ioctl(fd, UI_SET_KEYBIT, keyCodes[i]) < 0);
    }
    for (jsize i = 0; i < absCount; i++) {
        FAIL_IF(ioctl(fd, UI_SET_ABSBIT, absCodes[i]) < 0);
    }

    {
        /* Preferred path: UI_ABS_SETUP + UI_DEV_SETUP (kernel >= 4.5). */
        int modernOk = 1;
        for (jsize i = 0; i < absCount && modernOk; i++) {
            struct uinput_abs_setup absSetup;
            memset(&absSetup, 0, sizeof(absSetup));
            absSetup.code = (__u16) absCodes[i];
            absSetup.absinfo.minimum = absRanges[i * 4];
            absSetup.absinfo.maximum = absRanges[i * 4 + 1];
            absSetup.absinfo.fuzz = absRanges[i * 4 + 2];
            absSetup.absinfo.flat = absRanges[i * 4 + 3];
            if (ioctl(fd, UI_ABS_SETUP, &absSetup) < 0) {
                modernOk = 0;
            }
        }
        if (modernOk) {
            struct uinput_setup setup;
            memset(&setup, 0, sizeof(setup));
            setup.id.bustype = (__u16) busType;
            setup.id.vendor = (__u16) vendor;
            setup.id.product = (__u16) product;
            setup.id.version = (__u16) version;
            strncpy(setup.name, name, UINPUT_MAX_NAME_SIZE - 1);
            if (ioctl(fd, UI_DEV_SETUP, &setup) < 0) {
                modernOk = 0;
            }
        }

        if (!modernOk) {
            /* Legacy path, still accepted by every kernel that has uinput. */
            LOGI("UI_DEV_SETUP unavailable (%s), falling back to legacy write()", strerror(errno));
            struct uinput_user_dev legacy;
            memset(&legacy, 0, sizeof(legacy));
            strncpy(legacy.name, name, UINPUT_MAX_NAME_SIZE - 1);
            legacy.id.bustype = (__u16) busType;
            legacy.id.vendor = (__u16) vendor;
            legacy.id.product = (__u16) product;
            legacy.id.version = (__u16) version;
            for (jsize i = 0; i < absCount; i++) {
                int code = absCodes[i];
                if (code < 0 || code > ABS_MAX) {
                    continue;
                }
                legacy.absmin[code] = absRanges[i * 4];
                legacy.absmax[code] = absRanges[i * 4 + 1];
                legacy.absfuzz[code] = absRanges[i * 4 + 2];
                legacy.absflat[code] = absRanges[i * 4 + 3];
            }
            errno = 0;
            ssize_t written = write(fd, &legacy, sizeof(legacy));
            FAIL_IF(written != (ssize_t) sizeof(legacy));
        }
    }

    errno = 0;
    FAIL_IF(ioctl(fd, UI_DEV_CREATE) < 0);

#undef FAIL_IF

cleanup:
    if (keyCodes != NULL) {
        (*env)->ReleaseIntArrayElements(env, jkeyCodes, keyCodes, JNI_ABORT);
    }
    if (absCodes != NULL) {
        (*env)->ReleaseIntArrayElements(env, jabsCodes, absCodes, JNI_ABORT);
    }
    if (absRanges != NULL) {
        (*env)->ReleaseIntArrayElements(env, jabsRanges, absRanges, JNI_ABORT);
    }
    (*env)->ReleaseStringUTFChars(env, jname, name);
    return result;
}

/* Kernel-assigned sysfs name (e.g. "input42"), useful for diagnostics. */
JM_EXPORT JNIEXPORT jstring JNICALL
Java_com_joymerge_quest_nativebridge_NativeBridge_uinputSysName(JNIEnv *env, jclass clazz, jint fd) {
    char sysname[64];
    memset(sysname, 0, sizeof(sysname));
    if (ioctl(fd, UI_GET_SYSNAME(sizeof(sysname) - 1), sysname) < 0) {
        return NULL;
    }
    return (*env)->NewStringUTF(env, sysname);
}

JM_EXPORT JNIEXPORT jint JNICALL
Java_com_joymerge_quest_nativebridge_NativeBridge_uinputDestroy(JNIEnv *env, jclass clazz, jint fd) {
    errno = 0;
    if (ioctl(fd, UI_DEV_DESTROY) < 0) {
        return negative_errno();
    }
    return 0;
}

/*
 * Writes a batch of flat [type, code, value] triples. The caller is expected to
 * terminate each batch with an EV_SYN/SYN_REPORT triple; we do not add one so
 * that callers stay in control of report boundaries.
 */
JM_EXPORT JNIEXPORT jint JNICALL
Java_com_joymerge_quest_nativebridge_NativeBridge_uinputWrite(
        JNIEnv *env, jclass clazz, jint fd, jintArray jevents, jint count) {
    if (count <= 0 || count % 3 != 0) {
        return -EINVAL;
    }
    jsize capacity = (*env)->GetArrayLength(env, jevents);
    if (count > capacity) {
        return -EINVAL;
    }
    int eventCount = count / 3;
    if (eventCount > 128) {
        return -EINVAL;
    }

    jint *flat = (*env)->GetIntArrayElements(env, jevents, NULL);
    if (flat == NULL) {
        return -ENOMEM;
    }

    struct input_event events[128];
    memset(events, 0, sizeof(struct input_event) * (size_t) eventCount);
    for (int i = 0; i < eventCount; i++) {
        events[i].type = (__u16) flat[i * 3];
        events[i].code = (__u16) flat[i * 3 + 1];
        events[i].value = (__s32) flat[i * 3 + 2];
    }
    (*env)->ReleaseIntArrayElements(env, jevents, flat, JNI_ABORT);

    errno = 0;
    ssize_t written = write(fd, events, sizeof(struct input_event) * (size_t) eventCount);
    if (written < 0) {
        return negative_errno();
    }
    return (jint) (written / (ssize_t) sizeof(struct input_event));
}

/* Sanity check used by Diagnostics: is this build actually loaded? */
JM_EXPORT JNIEXPORT jstring JNICALL
Java_com_joymerge_quest_nativebridge_NativeBridge_nativeVersion(JNIEnv *env, jclass clazz) {
    return (*env)->NewStringUTF(env, "joymerge-native 1.0 (" __DATE__ ")");
}
