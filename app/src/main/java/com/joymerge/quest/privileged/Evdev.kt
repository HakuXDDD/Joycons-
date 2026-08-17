package com.joymerge.quest.privileged

import com.joymerge.quest.core.JoyConSide
import com.joymerge.quest.nativebridge.LinuxInput
import com.joymerge.quest.nativebridge.NativeBridge
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** One `/dev/input/eventN` node as we actually found it — including why it failed. */
data class EvdevDeviceInfo(
    val path: String,
    val exists: Boolean,
    val mode: String,
    val ownerUid: Int,
    val ownerGid: Int,
    val readable: Boolean,
    val writable: Boolean,
    val openError: String? = null,
    val name: String? = null,
    val uniq: String? = null,
    val bus: Int = 0,
    val vendor: Int = 0,
    val product: Int = 0,
    val version: Int = 0,
    val keyCodes: List<Int> = emptyList(),
    val absAxes: List<AbsAxisInfo> = emptyList(),
) {
    val opened: Boolean get() = openError == null && name != null

    val idString: String get() = "%04x:%04x".format(vendor, product)
}

data class AbsAxisInfo(
    val code: Int,
    val value: Int,
    val min: Int,
    val max: Int,
    val fuzz: Int,
    val flat: Int,
    val resolution: Int,
) {
    val label: String get() = LinuxInput.absName(code)
}

/**
 * Enumerates and opens evdev nodes.
 *
 * Reading /dev/input directly is what makes background operation possible at
 * all: once the user leaves JoyMerge for Xbox Cloud Gaming, Android stops
 * delivering key and motion events to us, but the kernel keeps writing to these
 * nodes regardless of which app has focus.
 */
object Evdev {

    const val INPUT_DIR = "/dev/input"
    private const val MAX_PROBE_INDEX = 63

    /** Lists the directory, falling back to probing eventN when listing is denied. */
    fun listPaths(): List<String> {
        val listed = runCatching { File(INPUT_DIR).listFiles() }.getOrNull()
        if (listed != null) {
            return listed.map { it.name }
                .filter { it.startsWith("event") }
                .sortedBy { it.removePrefix("event").toIntOrNull() ?: Int.MAX_VALUE }
                .map { "$INPUT_DIR/$it" }
        }
        return (0..MAX_PROBE_INDEX)
            .map { "$INPUT_DIR/event$it" }
            .filter { NativeBridge.statPath(it)?.get(0) == 1L }
    }

    fun describeDirectory(): String {
        val stat = NativeBridge.statPath(INPUT_DIR)
            ?: return "$INPUT_DIR: stat failed"
        if (stat[0] != 1L) {
            return "$INPUT_DIR: MISSING (errno ${stat[4]} ${NativeBridge.errnoMessage(stat[4].toInt())})"
        }
        return "$INPUT_DIR: ${LinuxInput.formatMode(stat[1])} uid=${stat[2]} gid=${stat[3]} " +
            "readable=${stat[5] == 1L} writable=${stat[6] == 1L}"
    }

    fun probe(path: String): EvdevDeviceInfo {
        val stat = NativeBridge.statPath(path)
        val exists = stat != null && stat[0] == 1L
        val mode = if (stat != null && stat[0] == 1L) LinuxInput.formatMode(stat[1]) else "?"
        val uid = stat?.get(2)?.toInt() ?: -1
        val gid = stat?.get(3)?.toInt() ?: -1
        val readable = stat?.get(5) == 1L
        val writable = stat?.get(6) == 1L

        if (!exists) {
            val errno = stat?.get(4)?.toInt() ?: 0
            return EvdevDeviceInfo(
                path, false, mode, uid, gid, readable, writable,
                openError = "stat failed: ${NativeBridge.errnoMessage(errno)}",
            )
        }

        val fd = NativeBridge.openFd(path, writable = false, nonBlocking = true)
        if (fd < 0) {
            return EvdevDeviceInfo(
                path, true, mode, uid, gid, readable, writable,
                openError = "open failed: ${NativeBridge.errnoMessage(fd)}",
            )
        }
        try {
            val id = NativeBridge.evdevId(fd) ?: IntArray(4)
            val absCodes = NativeBridge.evdevCodes(fd, LinuxInput.EV_ABS, LinuxInput.ABS_MAX) ?: IntArray(0)
            val absAxes = absCodes.toList().mapNotNull { code ->
                NativeBridge.evdevAbsInfo(fd, code)?.let {
                    AbsAxisInfo(code, it[0], it[1], it[2], it[3], it[4], it[5])
                }
            }
            return EvdevDeviceInfo(
                path = path,
                exists = true,
                mode = mode,
                ownerUid = uid,
                ownerGid = gid,
                readable = readable,
                writable = writable,
                openError = null,
                name = NativeBridge.evdevName(fd) ?: "(unnamed)",
                uniq = NativeBridge.evdevUniq(fd)?.takeIf { it.isNotBlank() },
                bus = id[0],
                vendor = id[1],
                product = id[2],
                version = id[3],
                keyCodes = (NativeBridge.evdevCodes(fd, LinuxInput.EV_KEY, LinuxInput.KEY_MAX) ?: IntArray(0)).toList(),
                absAxes = absAxes,
            )
        } finally {
            NativeBridge.closeFd(fd)
        }
    }

    fun probeAll(): List<EvdevDeviceInfo> = listPaths().map { probe(it) }
}

/**
 * One reader thread per Joy-Con.
 *
 * `grab` asks the kernel for exclusive access (EVIOCGRAB). With it, Horizon OS
 * stops seeing the two Joy-Cons as separate controllers while we merge them,
 * which is normally what you want; without it, games see three pads.
 */
class EvdevCapture(
    private val path: String,
    val side: JoyConSide,
    private val slot: Int,
    private val grab: Boolean,
    private val onEvent: (slot: Int, type: Int, code: Int, value: Int) -> Unit,
    private val onError: (slot: Int, message: String) -> Unit,
    private val onLog: (String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    @Volatile
    var fd: Int = -1
        private set

    @Volatile
    var grabbed: Boolean = false
        private set

    @Volatile
    var eventCount: Long = 0L
        private set

    val isRunning: Boolean get() = running.get()

    /** @return null on success, or the real failure reason. */
    fun start(): String? {
        if (running.get()) return null

        val opened = NativeBridge.openFd(path, writable = grab, nonBlocking = false)
        if (opened < 0) {
            // A grab needs write access; fall back to read-only without a grab.
            if (grab) {
                val readOnly = NativeBridge.openFd(path, writable = false, nonBlocking = false)
                if (readOnly >= 0) {
                    onLog("$path opened read-only; exclusive grab not possible")
                    fd = readOnly
                    return launch()
                }
            }
            return "open($path) failed: ${NativeBridge.errnoMessage(opened)}"
        }
        fd = opened

        if (grab) {
            val result = NativeBridge.evdevGrab(opened, true)
            grabbed = result == 0
            if (!grabbed) {
                onLog("EVIOCGRAB on $path failed: ${NativeBridge.errnoMessage(result)} - the system will still see this Joy-Con")
            }
        }
        return launch()
    }

    private fun launch(): String? {
        running.set(true)
        thread = Thread({ readLoop() }, "evdev-$slot-${path.substringAfterLast('/')}").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }
        return null
    }

    private fun readLoop() {
        val buffer = IntArray(3 * 64)
        while (running.get()) {
            val currentFd = fd
            if (currentFd < 0) break
            val count = NativeBridge.evdevRead(currentFd, buffer, POLL_TIMEOUT_MS)
            if (count < 0) {
                if (running.get()) {
                    onError(slot, "read($path) failed: ${NativeBridge.errnoMessage(count)}")
                }
                break
            }
            if (count == 0) continue
            var i = 0
            while (i + 2 < count) {
                eventCount++
                onEvent(slot, buffer[i], buffer[i + 1], buffer[i + 2])
                i += 3
            }
        }
        running.set(false)
    }

    fun stop() {
        running.set(false)
        thread?.let { runCatching { it.join(500) } }
        thread = null
        val currentFd = fd
        if (currentFd >= 0) {
            if (grabbed) NativeBridge.evdevGrab(currentFd, false)
            NativeBridge.closeFd(currentFd)
        }
        fd = -1
        grabbed = false
    }

    private companion object {
        const val POLL_TIMEOUT_MS = 200
    }
}
