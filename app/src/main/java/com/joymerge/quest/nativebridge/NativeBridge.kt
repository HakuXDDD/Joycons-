package com.joymerge.quest.nativebridge

import java.io.File

/**
 * JNI surface of `libjoymerge.so`.
 *
 * Every method that can fail returns a negative errno instead of throwing, so
 * the caller can report the *real* kernel error rather than a generic failure.
 *
 * Loading is deliberately defensive: this class is used both from the normal
 * app process and from the Shizuku user-service process (uid 2000). The latter
 * gets a bare class loader with no `java.library.path`, so `loadLibrary` alone
 * is not enough.
 */
object NativeBridge {

    const val LIB_NAME = "joymerge"
    private const val SO_NAME = "libjoymerge.so"

    /** Writable by the shell uid, and executable there — our last-resort home. */
    const val SHELL_LIB_DIR = "/data/local/tmp/joymerge"

    @Volatile
    private var loaded = false

    /** Human readable account of how loading went. Shown in Diagnostics. */
    @Volatile
    var loadReport: String = "not attempted"
        private set

    val isLoaded: Boolean get() = loaded

    /**
     * Attempts to load the native library.
     *
     * @param candidateDirs directories that may hold `libjoymerge.so`, most
     *   likely `ApplicationInfo.nativeLibraryDir`.
     * @param allowShellCopy when true (privileged process only) the library may
     *   be copied to [SHELL_LIB_DIR] if it cannot be loaded in place.
     */
    // UnsafeDynamicallyLoadedCode: we load our own .so by absolute path because
    // the Shizuku process has no java.library.path. SetWorldReadable: the shell
    // copy has to be readable by the shell uid, and it contains nothing secret.
    @Suppress("UnsafeDynamicallyLoadedCode", "SetWorldReadable")
    @Synchronized
    fun ensureLoaded(candidateDirs: List<String> = emptyList(), allowShellCopy: Boolean = false): Boolean {
        if (loaded) return true

        val attempts = mutableListOf<String>()

        runCatching {
            System.loadLibrary(LIB_NAME)
        }.onSuccess {
            loaded = true
            loadReport = "System.loadLibrary($LIB_NAME) OK"
            return true
        }.onFailure { attempts += "loadLibrary: ${it.message}" }

        for (dir in candidateDirs.distinct()) {
            val file = File(dir, SO_NAME)
            if (!file.exists()) {
                attempts += "$file: missing"
                continue
            }
            runCatching {
                System.load(file.absolutePath)
            }.onSuccess {
                loaded = true
                loadReport = "System.load($file) OK"
                return true
            }.onFailure { attempts += "$file: ${it.message}" }
        }

        if (allowShellCopy) {
            val source = candidateDirs.asSequence()
                .map { File(it, SO_NAME) }
                .firstOrNull { it.exists() && it.canRead() }
            if (source == null) {
                attempts += "shell copy: no readable source .so"
            } else {
                runCatching {
                    val targetDir = File(SHELL_LIB_DIR)
                    targetDir.mkdirs()
                    val target = File(targetDir, SO_NAME)
                    source.inputStream().use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    target.setReadable(true, false)
                    target.setExecutable(true, false)
                    System.load(target.absolutePath)
                    target
                }.onSuccess {
                    loaded = true
                    loadReport = "copied to $it and loaded OK"
                    return true
                }.onFailure { attempts += "shell copy: ${it.message}" }
            }
        }

        loadReport = "FAILED -> " + attempts.joinToString(" | ")
        return false
    }

    /** Directories worth probing for the .so, derived from an APK path. */
    fun candidateDirsFor(nativeLibraryDir: String?, sourceApk: String?): List<String> {
        val result = mutableListOf<String>()
        nativeLibraryDir?.let { result += it }
        sourceApk?.let { apk ->
            val base = File(apk).parentFile ?: return@let
            result += File(base, "lib/arm64").absolutePath
            result += File(base, "lib/arm64-v8a").absolutePath
        }
        result += SHELL_LIB_DIR
        return result
    }

    // ---- generic fd helpers -------------------------------------------------

    /** @return fd (>= 0) or -errno. */
    @JvmStatic external fun openFd(path: String, writable: Boolean, nonBlocking: Boolean): Int

    @JvmStatic external fun closeFd(fd: Int)

    @JvmStatic external fun errnoMessage(err: Int): String

    /** @return `[ok, mode, uid, gid, errno, readable, writable]` or null. */
    @JvmStatic external fun statPath(path: String): LongArray?

    @JvmStatic external fun getUid(): Int

    @JvmStatic external fun getEuid(): Int

    @JvmStatic external fun getGroups(): IntArray?

    // ---- evdev --------------------------------------------------------------

    @JvmStatic external fun evdevName(fd: Int): String?

    @JvmStatic external fun evdevUniq(fd: Int): String?

    /** @return `[bustype, vendor, product, version]` or null. */
    @JvmStatic external fun evdevId(fd: Int): IntArray?

    /** Supported codes for an `EV_*` type. */
    @JvmStatic external fun evdevCodes(fd: Int, evType: Int, maxCode: Int): IntArray?

    /** @return `[value, min, max, fuzz, flat, resolution]` or null. */
    @JvmStatic external fun evdevAbsInfo(fd: Int, axis: Int): IntArray?

    /** Exclusive grab; stops Android's own InputReader from seeing the device. */
    @JvmStatic external fun evdevGrab(fd: Int, grab: Boolean): Int

    /**
     * Polls then reads. Fills `out` with flat `[type, code, value]` triples.
     * @return number of ints written, 0 on timeout, or -errno.
     */
    @JvmStatic external fun evdevRead(fd: Int, out: IntArray, timeoutMs: Int): Int

    // ---- uinput -------------------------------------------------------------

    /** @return 0 on success or -errno of the first failing step. */
    @JvmStatic external fun uinputCreate(
        fd: Int,
        name: String,
        busType: Int,
        vendor: Int,
        product: Int,
        version: Int,
        keyCodes: IntArray,
        absCodes: IntArray,
        absRanges: IntArray,
    ): Int

    @JvmStatic external fun uinputSysName(fd: Int): String?

    @JvmStatic external fun uinputDestroy(fd: Int): Int

    /** @return events written, or -errno. */
    @JvmStatic external fun uinputWrite(fd: Int, events: IntArray, count: Int): Int

    @JvmStatic external fun nativeVersion(): String
}
