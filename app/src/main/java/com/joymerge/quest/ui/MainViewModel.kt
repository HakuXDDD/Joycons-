package com.joymerge.quest.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.joymerge.quest.JoyMergeApp
import com.joymerge.quest.JoyMergeController
import com.joymerge.quest.calibration.CalibrationSession
import com.joymerge.quest.core.JoyConSide
import com.joymerge.quest.core.MappingProfile
import com.joymerge.quest.core.SignalDomain
import com.joymerge.quest.core.VirtualGamepadState
import com.joymerge.quest.diagnostics.DiagnosticsManager
import com.joymerge.quest.diagnostics.DiagnosticsReport
import com.joymerge.quest.gamepad.BackendAvailability
import com.joymerge.quest.privileged.PrivilegedClient
import com.joymerge.quest.shizuku.ShizukuManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class Screen(val title: String) {
    MAIN("JoyMerge Quest"),
    CALIBRATION("Calibrate Joy-Cons"),
    TESTER("Gamepad Tester"),
    DIAGNOSTICS("Diagnostics"),
    MAPPING("Button mapping"),
    DEVICES("Joy-Con assignment"),
    SETTINGS("Virtual gamepad settings"),
    SHIZUKU_HELP("Setting up Shizuku"),
}

/** One row of the main screen. [ok] drives the colour, [value] the label. */
data class StatusRow(
    val label: String,
    val value: String,
    val ok: Boolean?,
    val detail: String = "",
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val controller: JoyMergeController = (application as JoyMergeApp).controller
    val diagnostics = DiagnosticsManager(application, controller)
    val calibration = CalibrationSession(controller, viewModelScope)

    private val _screen = MutableStateFlow(Screen.MAIN)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _report = MutableStateFlow<DiagnosticsReport?>(null)
    val report: StateFlow<DiagnosticsReport?> = _report.asStateFlow()

    private val _backendAvailability = MutableStateFlow<Map<String, BackendAvailability>>(emptyMap())
    val backendAvailability: StateFlow<Map<String, BackendAvailability>> = _backendAvailability.asStateFlow()

    val shizuku = controller.shizuku.info
    val engine = controller.engine
    val settings = controller.store.settings
    val profile = controller.store.profile
    val androidDevices = controller.inputDevices.devices
    val evdevDevices = controller.evdevDevices
    val privilegedConnection = controller.privileged.connection

    /** Whichever source is actually driving the pad right now. */
    val liveState: StateFlow<VirtualGamepadState> = combine(
        controller.privileged.state,
        controller.foregroundPipeline.state,
        controller.engine,
    ) { privilegedState, foregroundState, engineState ->
        if (engineState.running && engineState.usesPrivilegedCapture) privilegedState else foregroundState
    }.stateIn(viewModelScope, SharingStarted.Eagerly, VirtualGamepadState.NEUTRAL)

    /** Which physical device fills each slot, resolved for both input paths. */
    private data class SlotResolution(
        val androidAssignment: Map<JoyConSide, Int> = emptyMap(),
        val evdevPaths: Map<JoyConSide, String> = emptyMap(),
    )

    private val slots: StateFlow<SlotResolution> = combine(
        controller.inputDevices.devices,
        controller.evdevDevices,
        controller.store.settings,
    ) { _, evdev, settings ->
        SlotResolution(
            androidAssignment = controller.androidAssignment(),
            evdevPaths = controller.resolveEvdevPaths(evdev, settings),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SlotResolution())

    val statusRows: StateFlow<List<StatusRow>> = combine(
        controller.shizuku.info,
        controller.engine,
        controller.store.profile,
        controller.privileged.connection,
        slots,
    ) { shizukuInfo, engineState, profileBundle, connection, slotResolution ->
        buildStatusRows(shizukuInfo, engineState, profileBundle.androidProfile, connection, slotResolution)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        // Keep the foreground pipeline in step with whatever is stored.
        viewModelScope.launch {
            controller.store.profile.collect { bundle ->
                controller.foregroundPipeline.profile = bundle.androidProfile
                controller.applyForegroundAssignment()
            }
        }
        viewModelScope.launch {
            controller.inputDevices.devices.collect { controller.applyForegroundAssignment() }
        }
    }

    /**
     * Re-checks the privileged side. Called when the app comes back to the
     * foreground: the merge may have died while we were away (Shizuku stopped,
     * a Joy-Con dropped its Bluetooth link), and showing ACTIVE for something
     * that is not running would be the worst possible lie.
     */
    fun refreshEngineStatus() {
        viewModelScope.launch { controller.refreshEngineStatus() }
    }

    fun navigate(target: Screen) {
        if (_screen.value == Screen.CALIBRATION && target != Screen.CALIBRATION) {
            calibration.cancel()
        }
        _screen.value = target
        if (target == Screen.DIAGNOSTICS && _report.value == null) refreshDiagnostics()
    }

    fun dismissMessage() {
        _message.value = null
    }

    private fun buildStatusRows(
        shizukuInfo: ShizukuManager.Info,
        engineState: JoyMergeController.EngineState,
        androidProfile: MappingProfile,
        connection: PrivilegedClient.Connection,
        slotResolution: SlotResolution,
    ): List<StatusRow> {
        val assignment = slotResolution.androidAssignment
        val evdevPaths = slotResolution.evdevPaths

        fun joyConRow(side: JoyConSide): StatusRow {
            val androidDevice = assignment[side]?.let { controller.inputDevices.deviceById(it) }
            val evdevPath = evdevPaths[side]
            val connected = androidDevice != null || evdevPath != null
            val detail = buildString {
                append(androidDevice?.let { "Android: ${it.name} (id ${it.deviceId})" } ?: "Android: not assigned")
                append("  |  ")
                append(evdevPath?.let { "evdev: $it" } ?: "evdev: not resolved")
            }
            return StatusRow(
                label = side.label,
                value = if (connected) "CONNECTED" else "DISCONNECTED",
                ok = connected,
                detail = detail,
            )
        }

        val shizukuRow = StatusRow(
            label = "Shizuku",
            value = when (shizukuInfo.state) {
                ShizukuManager.State.READY -> "READY"
                ShizukuManager.State.NOT_INSTALLED -> "NOT INSTALLED"
                ShizukuManager.State.NOT_RUNNING -> "NOT RUNNING"
                ShizukuManager.State.PERMISSION_REQUIRED -> "PERMISSION NEEDED"
                ShizukuManager.State.PERMISSION_DENIED -> "PERMISSION DENIED"
            },
            ok = shizukuInfo.isReady,
            detail = shizukuInfo.detail,
        )

        val readerRow = when {
            engineState.running && engineState.usesPrivilegedCapture -> StatusRow(
                "Input reader",
                "READY",
                true,
                engineState.captureMessage,
            )

            engineState.running -> StatusRow(
                "Input reader",
                "FOREGROUND ONLY",
                null,
                engineState.captureMessage,
            )

            connection is PrivilegedClient.Connection.Connected && evdevPaths.isNotEmpty() -> StatusRow(
                "Input reader",
                "READY",
                true,
                "privileged reader connected; ${evdevPaths.size} Joy-Con node(s) resolved",
            )

            connection is PrivilegedClient.Connection.Failed -> StatusRow(
                "Input reader",
                "ERROR",
                false,
                connection.reason,
            )

            connection is PrivilegedClient.Connection.Connected -> StatusRow(
                "Input reader",
                "ERROR",
                false,
                controller.evdevError.value ?: "no Joy-Con nodes resolved under /dev/input",
            )

            assignment.isNotEmpty() && !androidProfile.isEmpty -> StatusRow(
                "Input reader",
                "FOREGROUND ONLY",
                null,
                "Android events work, but only while JoyMerge is focused. Connect Shizuku for background play.",
            )

            else -> StatusRow("Input reader", "NOT READY", false, "connect Shizuku and calibrate first")
        }

        val gamepadRow = StatusRow(
            label = "Virtual Gamepad",
            value = if (engineState.running) "ACTIVE" else "INACTIVE",
            ok = if (engineState.running) true else if (engineState.error != null) false else null,
            detail = engineState.error ?: engineState.backendMessage.ifBlank { "not started" },
        )

        return listOf(joyConRow(JoyConSide.LEFT), joyConRow(JoyConSide.RIGHT), shizukuRow, readerRow, gamepadRow)
    }

    // ---- actions -----------------------------------------------------------

    fun requestShizukuPermission() {
        if (!controller.shizuku.requestPermission()) {
            _message.value = "Could not ask Shizuku for permission. Is the Shizuku service running?"
        }
    }

    fun connectPrivileged() {
        launchBusy {
            when (val connection = controller.connectPrivileged()) {
                is PrivilegedClient.Connection.Connected -> {
                    controller.refreshEvdevDevices()
                    _message.value = "Privileged service connected. ${controller.nativeSummary()}"
                }

                is PrivilegedClient.Connection.Failed -> _message.value = connection.reason
                else -> _message.value = "Privileged service did not connect."
            }
        }
    }

    fun startGamepad() {
        launchBusy {
            val result = controller.startGamepad()
            _message.value = result.error ?: "Virtual gamepad started: ${result.backendMessage}"
            probeBackends()
        }
    }

    fun stopGamepad() {
        launchBusy {
            controller.stopGamepad()
            _message.value = "Virtual gamepad stopped."
        }
    }

    fun refreshDiagnostics() {
        launchBusy {
            controller.inputDevices.refresh()
            if (controller.privileged.service != null) controller.refreshEvdevDevices()
            _report.value = diagnostics.collect()
            probeBackends()
        }
    }

    fun probeBackends() {
        viewModelScope.launch {
            _backendAvailability.value = controller.backends.associate { it.id to it.probe() }
        }
    }

    fun selectBackend(id: String) {
        controller.store.updateSettings { it.copy(backendId = id) }
    }

    fun updateSettings(transform: (com.joymerge.quest.data.AppSettings) -> com.joymerge.quest.data.AppSettings) {
        controller.store.updateSettings(transform)
    }

    fun assignAndroidDevice(side: JoyConSide, descriptor: String?) =
        controller.assignAndroidDevice(side, descriptor)

    fun assignEvdevPath(side: JoyConSide, path: String?) = controller.assignEvdevPath(side, path)

    fun refreshEvdev() {
        launchBusy { controller.refreshEvdevDevices() }
    }

    fun clearCalibration() {
        controller.store.clearProfile()
        controller.foregroundPipeline.profile = MappingProfile(SignalDomain.ANDROID)
        _message.value = "Calibration profile cleared."
    }

    fun startCalibration(includeSideButtons: Boolean) {
        calibration.start(includeSideButtons)
    }

    fun saveCalibration() {
        if (calibration.finishAndSave()) {
            _message.value = "Calibration saved."
            _screen.value = Screen.MAIN
        } else {
            _message.value = "Nothing was captured, so nothing was saved."
        }
    }

    fun exportProfileText(): String = controller.store.exportProfileText()

    private fun launchBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
            } catch (t: Throwable) {
                _message.value = "Unexpected failure: ${t.message ?: t.toString()}"
            } finally {
                _busy.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        calibration.cancel()
    }
}

/** Short human summary of whether the privileged process got its native library. */
private fun JoyMergeController.nativeSummary(): String =
    if (privileged.nativeLoaded()) "Native layer loaded." else "WARNING: native layer NOT loaded - ${privileged.nativeReport()}"
