package com.joymerge.quest.privileged

import android.os.Build
import android.os.RemoteException
import android.os.SystemClock
import com.joymerge.quest.core.GamepadMerger
import com.joymerge.quest.core.JoyConSide
import com.joymerge.quest.core.MappingProfile
import com.joymerge.quest.core.ProfileCodec
import com.joymerge.quest.core.SignalDomain
import com.joymerge.quest.core.VirtualGamepadState
import com.joymerge.quest.nativebridge.LinuxInput
import com.joymerge.quest.nativebridge.NativeBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque

/**
 * Runs inside the Shizuku-hosted process as uid 2000 (shell).
 *
 * This class owns the entire hot path: it reads both Joy-Cons from
 * /dev/input, merges them with the calibrated profile, and writes the result to
 * /dev/uinput. The app process only sends configuration down and receives
 * throttled state for display, so putting JoyMerge in the background — which is
 * the whole point — costs nothing.
 */
class JoyMergeUserService : IJoyMergeService.Stub() {

    private val log = ArrayDeque<String>()

    /**
     * One reader thread per Joy-Con feeds this, so every touch of the merger and
     * of the uinput device happens under [mergeLock]. GamepadMerger is
     * deliberately not thread safe; serialising here keeps the hot path a single
     * cheap monitor rather than concurrent maps.
     */
    private val mergeLock = Any()
    private val merger = GamepadMerger(MappingProfile(SignalDomain.EVDEV))
    private val uinput = UinputGamepad()

    private val captures = java.util.concurrent.CopyOnWriteArrayList<EvdevCapture>()

    @Volatile
    private var listener: IJoyMergeListener? = null

    @Volatile
    private var streamRawEvents = false

    @Volatile
    private var injectionEnabled = false

    @Volatile
    private var injectionDeviceId = -1

    /** Guards the injector, which is stateful across frames. */
    private val injectionLock = Any()

    @Volatile
    private var lastInjectedState = VirtualGamepadState.NEUTRAL

    @Volatile
    private var lastStatePush = 0L

    @Volatile
    private var profileSummary = "none"

    @Volatile
    private var initReport: String = "not initialised"

    @Volatile
    private var grabRequested = false

    init {
        logLine("JoyMergeUserService created, uid=${android.os.Process.myUid()}")
    }

    // ---- lifecycle ---------------------------------------------------------

    override fun destroy() {
        logLine("destroy() requested")
        stopCapture()
        stopVirtualGamepad()
        stopInjectionBackend()
        listener = null
        System.exit(0)
    }

    override fun initialize(nativeLibDir: String?): String {
        val json = JSONObject()
        runCatching {
            // Hidden-API enforcement also applies to code loaded from an APK into
            // this process; without this, InputManager reflection is refused.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("")
            }
            json.put("hiddenApiBypass", true)
        }.onFailure {
            json.put("hiddenApiBypass", false)
            json.put("hiddenApiBypassError", it.message ?: it.toString())
        }

        val dirs = buildList {
            nativeLibDir?.takeIf { it.isNotBlank() }?.let { add(it) }
            add(NativeBridge.SHELL_LIB_DIR)
        }
        val loaded = NativeBridge.ensureLoaded(dirs, allowShellCopy = true)
        initReport = NativeBridge.loadReport
        json.put("nativeLoaded", loaded)
        json.put("nativeReport", NativeBridge.loadReport)
        if (loaded) {
            json.put("nativeVersion", runCatching { NativeBridge.nativeVersion() }.getOrElse { "?" })
        }
        json.put("uid", android.os.Process.myUid())
        logLine("initialize -> loaded=$loaded (${NativeBridge.loadReport})")
        return json.toString()
    }

    override fun describeEnvironment(): String {
        val json = JSONObject()
        json.put("processUid", android.os.Process.myUid())
        json.put("nativeLoaded", NativeBridge.isLoaded)
        json.put("nativeReport", initReport)
        if (NativeBridge.isLoaded) {
            json.put("uid", NativeBridge.getUid())
            json.put("euid", NativeBridge.getEuid())
            json.put("groups", JSONArray().apply { NativeBridge.getGroups()?.forEach { put(it) } })
        }
        json.put("selinuxContext", readFirstLine("/proc/self/attr/current") ?: "unavailable")
        json.put("kernel", readFirstLine("/proc/version") ?: "unavailable")
        json.put("androidRelease", Build.VERSION.RELEASE)
        json.put("sdkInt", Build.VERSION.SDK_INT)
        json.put("supportedAbis", JSONArray().apply { Build.SUPPORTED_ABIS.forEach { put(it) } })
        json.put("inputDirectory", if (NativeBridge.isLoaded) Evdev.describeDirectory() else "native not loaded")
        return json.toString()
    }

    override fun listInputDevices(): String {
        if (!NativeBridge.isLoaded) {
            return JSONObject().put("error", "native library not loaded: $initReport").toString()
        }
        val array = JSONArray()
        for (info in Evdev.probeAll()) {
            array.put(
                JSONObject().apply {
                    put("path", info.path)
                    put("exists", info.exists)
                    put("mode", info.mode)
                    put("uid", info.ownerUid)
                    put("gid", info.ownerGid)
                    put("readable", info.readable)
                    put("writable", info.writable)
                    put("opened", info.opened)
                    info.openError?.let { put("openError", it) }
                    info.name?.let { put("name", it) }
                    info.uniq?.let { put("uniq", it) }
                    put("bus", info.bus)
                    put("vendor", info.vendor)
                    put("product", info.product)
                    put("version", info.version)
                    put("keyCodes", JSONArray().apply { info.keyCodes.forEach { put(it) } })
                    put(
                        "absAxes",
                        JSONArray().apply {
                            info.absAxes.forEach { axis ->
                                put(
                                    JSONObject().apply {
                                        put("code", axis.code)
                                        put("value", axis.value)
                                        put("min", axis.min)
                                        put("max", axis.max)
                                        put("fuzz", axis.fuzz)
                                        put("flat", axis.flat)
                                        put("resolution", axis.resolution)
                                    },
                                )
                            }
                        },
                    )
                },
            )
        }
        return JSONObject().put("devices", array).toString()
    }

    override fun probeUinput(): String {
        val json = JSONObject()
        json.put("path", UinputGamepad.UINPUT_PATH)
        if (!NativeBridge.isLoaded) {
            return json.put("error", "native library not loaded: $initReport").toString()
        }
        val stat = NativeBridge.statPath(UinputGamepad.UINPUT_PATH)
        val exists = stat != null && stat[0] == 1L
        json.put("exists", exists)
        if (stat != null) {
            json.put("mode", if (exists) LinuxInput.formatMode(stat[1]) else "?")
            json.put("uid", stat[2])
            json.put("gid", stat[3])
            json.put("statErrno", stat[4])
            json.put("readable", stat[5] == 1L)
            json.put("writable", stat[6] == 1L)
        }
        if (!exists) {
            json.put("openResult", "SKIPPED - node does not exist")
            return json.toString()
        }
        // The only answer that counts: actually try to open it.
        val fd = NativeBridge.openFd(UinputGamepad.UINPUT_PATH, writable = true, nonBlocking = false)
        if (fd < 0) {
            json.put("openOk", false)
            json.put("openErrno", -fd)
            json.put("openResult", "UINPUT BLOCKED: ${NativeBridge.errnoMessage(fd)}")
        } else {
            NativeBridge.closeFd(fd)
            json.put("openOk", true)
            json.put("openResult", "open(O_RDWR) succeeded")
        }
        return json.toString()
    }

    // ---- capture -----------------------------------------------------------

    override fun startCapture(paths: Array<out String>?, sides: IntArray?, grab: Boolean): String {
        stopCapture()
        val json = JSONObject()
        if (!NativeBridge.isLoaded) {
            return json.put("ok", false).put("error", "native library not loaded: $initReport").toString()
        }
        if (paths == null || sides == null || paths.size != sides.size || paths.isEmpty()) {
            return json.put("ok", false).put("error", "paths/sides mismatch").toString()
        }

        grabRequested = grab
        val results = JSONArray()
        var opened = 0
        paths.forEachIndexed { index, path ->
            val side = JoyConSide.entries.getOrElse(sides[index]) { JoyConSide.LEFT }
            val capture = EvdevCapture(
                path = path,
                side = side,
                slot = index,
                grab = grab,
                // The side is captured here rather than looked up per event: the
                // capture list can change underneath a running reader thread.
                onEvent = { slot, type, code, value -> onRawEvent(side, slot, type, code, value) },
                onError = { slot, message -> onCaptureError(side, slot, message) },
                onLog = ::logLine,
            )
            val error = capture.start()
            results.put(
                JSONObject().apply {
                    put("path", path)
                    put("side", side.name)
                    put("slot", index)
                    put("ok", error == null)
                    put("grabbed", capture.grabbed)
                    error?.let { put("error", it) }
                },
            )
            if (error == null) {
                captures += capture
                opened++
            } else {
                logLine("capture failed for $path: $error")
            }
        }
        json.put("ok", opened > 0)
        json.put("opened", opened)
        json.put("results", results)
        logLine("startCapture: $opened/${paths.size} devices (grab=$grab)")
        return json.toString()
    }

    override fun stopCapture() {
        if (captures.isEmpty()) return
        captures.forEach { runCatching { it.stop() } }
        captures.clear()
        val state = synchronized(mergeLock) {
            merger.reset()
            merger.state
        }
        // Release everything, so nothing stays stuck down in the game.
        dispatch(state)
        pushState(state, force = true)
        logLine("capture stopped")
    }

    private fun onRawEvent(side: JoyConSide, slot: Int, type: Int, code: Int, value: Int) {
        if (streamRawEvents && type != LinuxInput.EV_SYN) {
            runCatching { listener?.onRawEvent(slot, type, code, value) }
        }
        val state = synchronized(mergeLock) {
            val changed = when (type) {
                LinuxInput.EV_KEY -> merger.onKey(side, code, value != 0)
                LinuxInput.EV_ABS -> merger.onAxis(side, code, value.toFloat())
                else -> false
            }
            if (changed) merger.state else null
        } ?: return
        dispatch(state)
        pushState(state, force = false)
    }

    private fun onCaptureError(side: JoyConSide, slot: Int, message: String) {
        logLine("slot $slot (${side.label}): $message")
        val state = synchronized(mergeLock) {
            if (merger.clearSide(side)) merger.state else null
        }
        if (state != null) {
            dispatch(state)
            pushState(state, force = true)
        }
        runCatching { listener?.onCaptureError(slot, message) }
    }

    /** Single funnel: whatever produced a new state, it goes out from here. */
    private fun dispatch(state: VirtualGamepadState) {
        if (uinput.isActive) uinput.submit(state)
        if (injectionEnabled) {
            synchronized(injectionLock) {
                val error = InputInjector.submit(lastInjectedState, state, injectionDeviceId)
                lastInjectedState = state
                if (error != null) logLine("inject failed: $error")
            }
        }
    }

    private fun pushState(state: VirtualGamepadState, force: Boolean) {
        val target = listener ?: return
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastStatePush < STATE_PUSH_INTERVAL_MS) return
        lastStatePush = now
        runCatching { target.onState(state.toFrame()) }
            .onFailure { if (it is RemoteException) listener = null }
    }

    // ---- virtual gamepad ---------------------------------------------------

    override fun startVirtualGamepad(
        deviceName: String?,
        busType: Int,
        vendor: Int,
        product: Int,
        version: Int,
        duplicateTriggerAxes: Boolean,
    ): String {
        val config = UinputGamepad.Config(
            deviceName = deviceName?.takeIf { it.isNotBlank() } ?: UinputGamepad.DEFAULT_NAME,
            busType = busType,
            vendorId = vendor,
            productId = product,
            version = version,
            duplicateTriggerAxes = duplicateTriggerAxes,
        )
        val error = uinput.open(config)
        val status = uinput.status()
        val json = JSONObject()
            .put("ok", error == null)
            .put("active", status.active)
            .put("fd", status.fd)
            .put("sysName", status.sysName ?: JSONObject.NULL)
            .put("deviceName", config.deviceName)
        if (error != null) {
            json.put("error", error)
            json.put("errno", status.errno)
            logLine("startVirtualGamepad FAILED: $error")
        } else {
            logLine("virtual gamepad created: ${config.deviceName} (${status.sysName})")
            uinput.submit(synchronized(mergeLock) { merger.state }, force = true)
        }
        return json.toString()
    }

    override fun stopVirtualGamepad() {
        if (uinput.isActive) {
            uinput.close()
            logLine("virtual gamepad destroyed")
        }
    }

    override fun startInjectionBackend(): String {
        val error = InputInjector.prepare()
        injectionEnabled = error == null
        lastInjectedState = VirtualGamepadState.NEUTRAL
        val json = JSONObject().put("ok", error == null)
        if (error != null) {
            json.put("error", error)
            logLine("injection backend unavailable: $error")
        } else {
            logLine("injection backend ready")
        }
        return json.toString()
    }

    override fun stopInjectionBackend() {
        if (!injectionEnabled) return
        injectionEnabled = false
        synchronized(injectionLock) {
            // Lift every button we pressed, or the game keeps them held forever.
            runCatching { InputInjector.releaseAll(lastInjectedState, injectionDeviceId) }
            lastInjectedState = VirtualGamepadState.NEUTRAL
        }
        logLine("injection backend stopped")
    }

    override fun submitFrame(frame: IntArray?) {
        if (frame == null || frame.size < VirtualGamepadState.FRAME_SIZE) return
        dispatch(VirtualGamepadState.fromFrame(frame))
    }

    // ---- configuration -----------------------------------------------------

    override fun setProfile(profileText: String?) {
        if (profileText.isNullOrBlank()) {
            synchronized(mergeLock) { merger.profile = MappingProfile(SignalDomain.EVDEV) }
            profileSummary = "none"
            return
        }
        val bundle = ProfileCodec.decode(profileText)
        synchronized(mergeLock) { merger.profile = bundle.evdevProfile }
        profileSummary = "${bundle.evdevProfile.boundControlCount} controls bound"
        logLine("profile installed: $profileSummary")
    }

    override fun setListener(newListener: IJoyMergeListener?) {
        listener = newListener
        if (newListener != null) pushState(synchronized(mergeLock) { merger.state }, force = true)
    }

    override fun setRawEventStreaming(enabled: Boolean) {
        streamRawEvents = enabled
    }

    override fun status(): String {
        val uinputStatus = uinput.status()
        val slots = JSONArray()
        captures.forEachIndexed { index, capture ->
            slots.put(
                JSONObject().apply {
                    put("slot", index)
                    put("side", capture.side.name)
                    put("running", capture.isRunning)
                    put("grabbed", capture.grabbed)
                    put("fd", capture.fd)
                    put("events", capture.eventCount)
                },
            )
        }
        return JSONObject().apply {
            put("uid", android.os.Process.myUid())
            put("nativeLoaded", NativeBridge.isLoaded)
            put("capturing", captures.any { it.isRunning })
            put("grabRequested", grabRequested)
            put("slots", slots)
            put("profile", profileSummary)
            put("uinputActive", uinputStatus.active)
            put("uinputSysName", uinputStatus.sysName ?: JSONObject.NULL)
            uinputStatus.error?.let { put("uinputError", it) }
            put("injectionEnabled", injectionEnabled)
            InputInjector.lastError?.let { put("injectionError", it) }
            put(
                "state",
                JSONArray().apply {
                    synchronized(mergeLock) { merger.state }.toFrame().forEach { put(it) }
                },
            )
        }.toString()
    }

    override fun drainLog(): String {
        synchronized(log) {
            if (log.isEmpty()) return ""
            val text = log.joinToString("\n")
            log.clear()
            return text
        }
    }

    private fun logLine(message: String) {
        val line = "[${SystemClock.elapsedRealtime()}] $message"
        synchronized(log) {
            log.addLast(line)
            while (log.size > LOG_CAPACITY) log.removeFirst()
        }
        runCatching { listener?.onLog(line) }
    }

    private fun readFirstLine(path: String): String? =
        runCatching { File(path).readText().trim().takeIf { it.isNotEmpty() } }.getOrNull()

    private companion object {
        const val LOG_CAPACITY = 300

        /** ~30 Hz to the UI; the uinput path itself is not throttled. */
        const val STATE_PUSH_INTERVAL_MS = 33L
    }
}
