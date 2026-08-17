package com.joymerge.quest

import android.content.Context
import android.util.Log
import com.joymerge.quest.core.JoyConDetector
import com.joymerge.quest.core.JoyConSide
import com.joymerge.quest.core.ProfileCodec
import com.joymerge.quest.core.VirtualGamepadState
import com.joymerge.quest.data.AppSettings
import com.joymerge.quest.data.JoyMergeStore
import com.joymerge.quest.gamepad.BackendConfig
import com.joymerge.quest.gamepad.BackendResult
import com.joymerge.quest.gamepad.LoopbackBackend
import com.joymerge.quest.gamepad.PrivilegedInjectionBackend
import com.joymerge.quest.gamepad.UInputBackend
import com.joymerge.quest.gamepad.VirtualGamepadBackend
import com.joymerge.quest.input.AndroidDeviceInfo
import com.joymerge.quest.input.InputDeviceManager
import com.joymerge.quest.input.ForegroundInputPipeline
import com.joymerge.quest.privileged.EvdevDeviceInfo
import com.joymerge.quest.privileged.EvdevJson
import com.joymerge.quest.privileged.PrivilegedClient
import com.joymerge.quest.service.GamepadForegroundService
import com.joymerge.quest.shizuku.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The one object that knows how all the pieces fit together.
 *
 * Nothing here reports a working controller unless the layer underneath
 * actually said so. Every start path returns either a concrete success message
 * (including the kernel device name where there is one) or the real reason it
 * failed, and that string is what the UI shows.
 */
class JoyMergeController(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val startStopLock = Mutex()

    val store = JoyMergeStore(appContext)
    val shizuku = ShizukuManager(appContext)
    val privileged = PrivilegedClient(appContext)
    val inputDevices = InputDeviceManager(appContext)
    val foregroundPipeline = ForegroundInputPipeline()

    val backends: List<VirtualGamepadBackend> = listOf(
        UInputBackend(privileged),
        PrivilegedInjectionBackend(privileged),
        LoopbackBackend(),
    )

    private val _engine = MutableStateFlow(EngineState())
    val engine: StateFlow<EngineState> = _engine.asStateFlow()

    private val _evdevDevices = MutableStateFlow<List<EvdevDeviceInfo>>(emptyList())
    val evdevDevices: StateFlow<List<EvdevDeviceInfo>> = _evdevDevices.asStateFlow()

    private val _evdevError = MutableStateFlow<String?>(null)
    val evdevError: StateFlow<String?> = _evdevError.asStateFlow()

    data class EngineState(
        val running: Boolean = false,
        val backendId: String = "",
        val backendMessage: String = "",
        val captureMessage: String = "",
        val usesPrivilegedCapture: Boolean = false,
        val leftCaptured: Boolean = false,
        val rightCaptured: Boolean = false,
        val exclusiveGrab: Boolean = false,
        val error: String? = null,
        val startedAt: Long = 0L,
    )

    fun backendById(id: String): VirtualGamepadBackend =
        backends.firstOrNull { it.id == id } ?: backends.first()

    fun start() {
        shizuku.start()
        inputDevices.start()
        applyForegroundAssignment()
    }

    fun stop() {
        shizuku.stop()
        inputDevices.stop()
    }

    // ---- device assignment -------------------------------------------------

    /**
     * Works out which Android device fills each slot: an explicit choice from
     * the user first, automatic detection second.
     *
     * Pure with respect to its arguments so callers that already hold a devices
     * list can resolve slots without depending on when the pipeline was last
     * updated.
     */
    fun resolveAndroidAssignment(
        devices: List<AndroidDeviceInfo>,
        settings: AppSettings,
    ): Map<JoyConSide, Int> {
        val detected = JoyConDetector.assignSides(devices) { it.toCandidate() }
        val left = devices.firstOrNull { it.descriptor == settings.leftAndroidDescriptor }
            ?: detected[JoyConSide.LEFT]
        val right = devices.firstOrNull { it.descriptor == settings.rightAndroidDescriptor }
            ?: detected[JoyConSide.RIGHT]
        return buildMap {
            left?.let { put(JoyConSide.LEFT, it.deviceId) }
            right?.let { put(JoyConSide.RIGHT, it.deviceId) }
        }
    }

    /** Applies the stored (or auto-detected) Android device -> side assignment. */
    fun applyForegroundAssignment() {
        val assignment = resolveAndroidAssignment(inputDevices.devices.value, store.settings.value)
        foregroundPipeline.assignment = assignment.entries.associate { (side, id) -> id to side }
    }

    fun assignAndroidDevice(side: JoyConSide, descriptor: String?) {
        store.updateSettings {
            when (side) {
                JoyConSide.LEFT -> it.copy(leftAndroidDescriptor = descriptor)
                JoyConSide.RIGHT -> it.copy(rightAndroidDescriptor = descriptor)
            }
        }
        applyForegroundAssignment()
    }

    fun assignEvdevPath(side: JoyConSide, path: String?) {
        store.updateSettings {
            when (side) {
                JoyConSide.LEFT -> it.copy(leftEvdevPath = path)
                JoyConSide.RIGHT -> it.copy(rightEvdevPath = path)
            }
        }
    }

    /** Currently assigned Android devices, keyed by side. */
    fun androidAssignment(): Map<JoyConSide, Int> = foregroundPipeline.assignment
        .entries.associate { (deviceId, side) -> side to deviceId }

    // ---- privileged connection --------------------------------------------

    suspend fun connectPrivileged(): PrivilegedClient.Connection {
        shizuku.refresh()
        val info = shizuku.info.value
        if (!info.isReady) {
            val failure = PrivilegedClient.Connection.Failed(
                "Shizuku is not ready: ${info.state} - ${info.detail}",
            )
            return failure
        }
        return privileged.connect()
    }

    /** Refreshes the privileged view of /dev/input. */
    suspend fun refreshEvdevDevices(): List<EvdevDeviceInfo> = withContext(Dispatchers.IO) {
        val service = privileged.service
        if (service == null) {
            _evdevError.value = "privileged service is not connected"
            _evdevDevices.value = emptyList()
            return@withContext emptyList()
        }
        val parsed = EvdevJson.parseDeviceList(runCatching { service.listInputDevices() }.getOrNull())
        _evdevError.value = parsed.error
        _evdevDevices.value = parsed.devices
        parsed.devices
    }

    /** Best guess at the two evdev nodes, honouring any manual override. */
    fun resolveEvdevPaths(devices: List<EvdevDeviceInfo>, settings: AppSettings): Map<JoyConSide, String> {
        val detected = JoyConDetector.assignSides(EvdevJson.gamepadCandidates(devices)) {
            EvdevJson.toCandidate(it)
        }
        return buildMap {
            val left = settings.leftEvdevPath ?: detected[JoyConSide.LEFT]?.path
            val right = settings.rightEvdevPath ?: detected[JoyConSide.RIGHT]?.path
            left?.let { put(JoyConSide.LEFT, it) }
            right?.let { put(JoyConSide.RIGHT, it) }
        }
    }

    // ---- start / stop ------------------------------------------------------

    suspend fun startGamepad(): EngineState = startStopLock.withLock {
        val settings = store.settings.value
        val backend = backendById(settings.backendId)
        val profileBundle = store.profile.value

        if (profileBundle.isEmpty) {
            return@withLock fail("No calibration profile yet - run CALIBRATE first.")
        }

        // Keep the process alive before anything else; otherwise Android may kill
        // us the moment the user switches to Xbox Cloud Gaming.
        GamepadForegroundService.start(appContext)

        if (!backend.requiresShizuku) {
            return@withLock startForegroundOnly(backend, profileBundle)
        }

        val connection = connectPrivileged()
        if (connection !is PrivilegedClient.Connection.Connected) {
            GamepadForegroundService.stop(appContext)
            return@withLock fail(
                when (connection) {
                    is PrivilegedClient.Connection.Failed -> connection.reason
                    else -> "could not connect to the privileged service"
                },
            )
        }

        val service = connection.service

        if (backend.id == UINPUT_BACKEND_ID && !privileged.nativeLoaded()) {
            GamepadForegroundService.stop(appContext)
            return@withLock fail("Native library did not load in the privileged process: ${privileged.nativeReport()}")
        }

        runCatching { service.setProfile(ProfileCodec.encode(profileBundle)) }
            .onFailure {
                GamepadForegroundService.stop(appContext)
                return@withLock fail("could not install the calibration profile: ${it.message}")
            }

        val devices = refreshEvdevDevices()
        val paths = resolveEvdevPaths(devices, settings)
        if (paths.isEmpty()) {
            GamepadForegroundService.stop(appContext)
            return@withLock fail(
                _evdevError.value?.let { "Cannot read /dev/input: $it" }
                    ?: "No Joy-Con evdev nodes found. Open Diagnostics and assign them by hand.",
            )
        }

        val orderedSides = paths.keys.toList()
        val captureReply = runCatching {
            service.startCapture(
                orderedSides.map { paths.getValue(it) }.toTypedArray(),
                orderedSides.map { it.ordinal }.toIntArray(),
                settings.exclusiveGrab,
            )
        }.getOrElse {
            GamepadForegroundService.stop(appContext)
            return@withLock fail("startCapture threw: ${it.message}")
        }

        val captureJson = runCatching { JSONObject(captureReply) }.getOrNull()
        if (captureJson?.optBoolean("ok", false) != true) {
            runCatching { service.stopCapture() }
            GamepadForegroundService.stop(appContext)
            return@withLock fail(describeCaptureFailure(captureJson, captureReply))
        }

        val backendResult = backend.start(
            BackendConfig(
                deviceName = settings.deviceName,
                identity = settings.identity,
                duplicateTriggerAxes = settings.duplicateTriggerAxes,
            ),
        )
        if (backendResult !is BackendResult.Started) {
            runCatching { service.stopCapture() }
            GamepadForegroundService.stop(appContext)
            return@withLock fail(backendResult.message)
        }

        val grabbed = captureJson.optJSONArray("results")
        var left = false
        var right = false
        var anyGrabbed = false
        if (grabbed != null) {
            for (i in 0 until grabbed.length()) {
                val item = grabbed.optJSONObject(i) ?: continue
                if (!item.optBoolean("ok", false)) continue
                if (item.optBoolean("grabbed", false)) anyGrabbed = true
                when (item.optString("side")) {
                    JoyConSide.LEFT.name -> left = true
                    JoyConSide.RIGHT.name -> right = true
                }
            }
        }

        val next = EngineState(
            running = true,
            backendId = backend.id,
            backendMessage = backendResult.message,
            captureMessage = "reading ${captureJson.optInt("opened")} of ${paths.size} device(s) from /dev/input",
            usesPrivilegedCapture = true,
            leftCaptured = left,
            rightCaptured = right,
            exclusiveGrab = anyGrabbed,
            error = null,
            startedAt = System.currentTimeMillis(),
        )
        _engine.value = next
        GamepadForegroundService.updateStatus(appContext, next)
        next
    }

    private suspend fun startForegroundOnly(
        backend: VirtualGamepadBackend,
        profileBundle: ProfileCodec.Bundle,
    ): EngineState {
        foregroundPipeline.profile = profileBundle.androidProfile
        applyForegroundAssignment()
        val result = backend.start(BackendConfig())
        if (result !is BackendResult.Started) {
            GamepadForegroundService.stop(appContext)
            return fail(result.message)
        }
        val assigned = foregroundPipeline.assignment.values.toSet()
        val next = EngineState(
            running = true,
            backendId = backend.id,
            backendMessage = result.message,
            captureMessage = "reading Android input events (only while JoyMerge is in the foreground)",
            usesPrivilegedCapture = false,
            leftCaptured = JoyConSide.LEFT in assigned,
            rightCaptured = JoyConSide.RIGHT in assigned,
            exclusiveGrab = false,
            startedAt = System.currentTimeMillis(),
        )
        _engine.value = next
        GamepadForegroundService.updateStatus(appContext, next)
        return next
    }

    suspend fun stopGamepad() = startStopLock.withLock {
        val current = _engine.value
        if (current.running) {
            val backend = backendById(current.backendId)
            runCatching { backend.stop() }
        }
        runCatching { privileged.service?.stopCapture() }
        runCatching { privileged.service?.stopVirtualGamepad() }
        runCatching { privileged.service?.stopInjectionBackend() }
        foregroundPipeline.reset()
        GamepadForegroundService.stop(appContext)
        _engine.value = EngineState()
    }

    /** Reconciles our idea of "running" with what the privileged side reports. */
    suspend fun refreshEngineStatus() = withContext(Dispatchers.IO) {
        val current = _engine.value
        if (!current.running || !current.usesPrivilegedCapture) return@withContext
        val service = privileged.service ?: run {
            _engine.value = current.copy(running = false, error = "privileged service disconnected")
            return@withContext
        }
        val json = runCatching { JSONObject(service.status()) }.getOrNull() ?: return@withContext
        val capturing = json.optBoolean("capturing", false)
        val uinputActive = json.optBoolean("uinputActive", false)
        val injecting = json.optBoolean("injectionEnabled", false)
        val stillUp = capturing && (uinputActive || injecting || current.backendId == LOOPBACK_BACKEND_ID)
        if (!stillUp) {
            _engine.value = current.copy(
                running = false,
                error = json.optString("uinputError").takeIf { it.isNotBlank() }
                    ?: "the privileged merge stopped (capturing=$capturing, uinput=$uinputActive, inject=$injecting)",
            )
        }
    }

    private fun describeCaptureFailure(json: JSONObject?, raw: String): String {
        val results = json?.optJSONArray("results") ?: return "could not open the Joy-Con input nodes: $raw"
        val reasons = (0 until results.length()).mapNotNull { index ->
            val item = results.optJSONObject(index) ?: return@mapNotNull null
            item.optString("error").takeIf { it.isNotBlank() }?.let { "${item.optString("path")}: $it" }
        }
        return if (reasons.isEmpty()) {
            json.optString("error", "could not open the Joy-Con input nodes")
        } else {
            "INPUT READER FAILED - " + reasons.joinToString("; ")
        }
    }

    private fun fail(reason: String): EngineState {
        Log.w(TAG, "start failed: $reason")
        val next = EngineState(running = false, error = reason)
        _engine.value = next
        return next
    }

    companion object {
        private const val TAG = "JoyMergeController"
        const val UINPUT_BACKEND_ID = "uinput"
        const val INJECTION_BACKEND_ID = "injection"
        const val LOOPBACK_BACKEND_ID = "loopback"
    }
}
