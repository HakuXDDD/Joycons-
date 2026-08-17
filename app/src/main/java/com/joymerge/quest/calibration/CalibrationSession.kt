package com.joymerge.quest.calibration

import com.joymerge.quest.JoyMergeController
import com.joymerge.quest.core.AxisInfo
import com.joymerge.quest.core.CalibrationEngine
import com.joymerge.quest.core.CalibrationStep
import com.joymerge.quest.core.CalibrationSteps
import com.joymerge.quest.core.CaptureResult
import com.joymerge.quest.core.JoyConSide
import com.joymerge.quest.core.SignalDomain
import com.joymerge.quest.nativebridge.LinuxInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

data class CalibrationUiState(
    val active: Boolean = false,
    val steps: List<CalibrationStep> = emptyList(),
    val index: Int = 0,
    val phase: Int = 0,
    val androidActive: Boolean = false,
    val androidDetail: String = "not started",
    val evdevActive: Boolean = false,
    val evdevDetail: String = "not started",
    val captured: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val finished: Boolean = false,
) {
    val currentStep: CalibrationStep? get() = steps.getOrNull(index)

    val prompt: String
        get() = currentStep?.phasePrompts?.getOrNull(phase)
            ?: currentStep?.phasePrompts?.firstOrNull()
            ?: ""

    val progressLabel: String get() = if (steps.isEmpty()) "" else "${index + 1} / ${steps.size}"
}

/**
 * Runs a calibration pass over both signal vocabularies at once.
 *
 * Pressing A once has to teach us two different things: the Android key code
 * the Quest reports to a focused app, and the raw evdev code the kernel writes
 * to /dev/input. Only the second one is usable in the background, but the first
 * is what makes the Tester work with no Shizuku at all — so both engines watch
 * the same physical press and each records it in its own vocabulary.
 */
class CalibrationSession(
    private val controller: JoyMergeController,
    private val scope: CoroutineScope,
) {

    private val androidEngine = CalibrationEngine(SignalDomain.ANDROID)
    private val evdevEngine = CalibrationEngine(SignalDomain.EVDEV)

    private val _state = MutableStateFlow(CalibrationUiState())
    val state: StateFlow<CalibrationUiState> = _state.asStateFlow()

    /** Slot index -> side, as passed to the privileged startCapture. */
    private var slotSides: List<JoyConSide> = emptyList()

    private var advanceJob: Job? = null
    private var evdevJob: Job? = null
    private var captureStartedByUs = false

    private val lock = Any()

    // ---- lifecycle ---------------------------------------------------------

    /** Full run from scratch: every previous binding is discarded. */
    fun start(includeSideButtons: Boolean) = start(
        steps = CalibrationSteps.default() +
            if (includeSideButtons) CalibrationSteps.sideButtons() else emptyList(),
        preserveExisting = false,
    )

    /** Re-learns one control, leaving every other binding as it was. */
    fun startSingle(step: CalibrationStep) = start(listOf(step), preserveExisting = true)

    fun start(steps: List<CalibrationStep>, preserveExisting: Boolean) {
        if (steps.isEmpty()) return
        val stored = controller.store.profile.value
        synchronized(lock) {
            androidEngine.reset()
            evdevEngine.reset()
            if (preserveExisting) {
                androidEngine.loadProfile(stored.androidProfile)
                evdevEngine.loadProfile(stored.evdevProfile)
            }
        }
        _state.value = CalibrationUiState(active = true, steps = steps, index = 0, phase = 0)

        attachAndroidSource()
        scope.launch { attachEvdevSource() }
        beginStep(0)
    }

    fun cancel() {
        advanceJob?.cancel()
        controller.foregroundPipeline.signalListener = null
        evdevJob?.cancel()
        evdevJob = null
        if (captureStartedByUs) {
            runCatching { controller.privileged.service?.setRawEventStreaming(false) }
            runCatching { controller.privileged.service?.stopCapture() }
            captureStartedByUs = false
        }
        _state.update { it.copy(active = false) }
    }

    /** Writes both profiles into the store and ends the session. */
    fun finishAndSave(): Boolean {
        val androidProfile = synchronized(lock) { androidEngine.profile }
        val evdevProfile = synchronized(lock) { evdevEngine.profile }
        if (androidProfile.isEmpty && evdevProfile.isEmpty) {
            addWarning("Nothing was captured - the profile was not saved.")
            return false
        }
        val settings = controller.store.settings.value
        controller.store.saveProfile(
            controller.store.profile.value.copy(
                createdAt = System.currentTimeMillis(),
                androidProfile = androidProfile,
                evdevProfile = evdevProfile,
                leftDeviceKey = settings.leftEvdevPath ?: settings.leftAndroidDescriptor,
                rightDeviceKey = settings.rightEvdevPath ?: settings.rightAndroidDescriptor,
                notes = _state.value.warnings,
            ),
        )
        cancel()
        _state.update { it.copy(finished = true) }
        return true
    }

    // ---- step control ------------------------------------------------------

    fun skipStep() {
        val current = _state.value
        synchronized(lock) {
            androidEngine.skipStep()
            evdevEngine.skipStep()
        }
        addCapture("skipped: ${current.currentStep?.targetLabel ?: ""}")
        advanceTo(current.index + 1)
    }

    fun previousStep() {
        advanceTo((_state.value.index - 1).coerceAtLeast(0))
    }

    private fun beginStep(index: Int) {
        val steps = _state.value.steps
        val step = steps.getOrNull(index) ?: return
        synchronized(lock) {
            androidEngine.beginStep(step)
            evdevEngine.beginStep(step)
        }
        _state.update { it.copy(index = index, phase = 0) }
    }

    private fun advanceTo(index: Int) {
        advanceJob?.cancel()
        val steps = _state.value.steps
        if (index >= steps.size) {
            _state.update { it.copy(index = steps.size, phase = 0) }
            return
        }
        beginStep(index)
    }

    /**
     * A step is done as soon as *either* vocabulary recognised it, but we wait a
     * moment first so the other one can record the same press.
     */
    private fun scheduleAdvance() {
        if (advanceJob?.isActive == true) return
        advanceJob = scope.launch {
            delay(GRACE_MS)
            advanceTo(_state.value.index + 1)
        }
    }

    // ---- signal sources ----------------------------------------------------

    private fun attachAndroidSource() {
        val devices = controller.inputDevices.devices.value
        val assignment = controller.androidAssignment()
        var bound = 0
        for ((side, deviceId) in assignment) {
            val device = devices.firstOrNull { it.deviceId == deviceId } ?: continue
            bound++
            device.axes.forEach { axis ->
                synchronized(lock) { androidEngine.setAxisInfo(side, axis.axis, axis.toCoreAxisInfo()) }
            }
        }
        controller.foregroundPipeline.signalListener = { side, isAxis, code, value ->
            handleSignal(androidEngine, side, isAxis, code, value)
        }
        _state.update {
            it.copy(
                androidActive = bound > 0,
                androidDetail = if (bound > 0) {
                    "$bound Joy-Con(s) assigned; keep JoyMerge focused"
                } else {
                    "no Joy-Con assigned to an Android input device"
                },
            )
        }
    }

    private suspend fun attachEvdevSource() {
        val service = controller.privileged.service
        if (service == null) {
            _state.update {
                it.copy(
                    evdevActive = false,
                    evdevDetail = "privileged service not connected - background mapping will not be learned",
                )
            }
            return
        }

        val devices = controller.refreshEvdevDevices()
        val paths = controller.resolveEvdevPaths(devices, controller.store.settings.value)
        if (paths.isEmpty()) {
            _state.update {
                it.copy(evdevActive = false, evdevDetail = "no Joy-Con nodes found under /dev/input")
            }
            return
        }

        slotSides = paths.keys.toList()
        paths.forEach { (side, path) ->
            devices.firstOrNull { it.path == path }?.absAxes?.forEach { axis ->
                synchronized(lock) {
                    evdevEngine.setAxisInfo(side, axis.code, AxisInfo(axis.min.toFloat(), axis.max.toFloat(), axis.flat.toFloat()))
                }
            }
        }

        // No exclusive grab while calibrating: both vocabularies need to see the
        // same press, and grabbing would starve the Android side.
        val reply = runCatching {
            service.startCapture(
                slotSides.map { paths.getValue(it) }.toTypedArray(),
                slotSides.map { it.ordinal }.toIntArray(),
                false,
            )
        }.getOrNull()
        val ok = runCatching { JSONObject(reply ?: "{}").optBoolean("ok", false) }.getOrDefault(false)
        if (!ok) {
            _state.update {
                it.copy(evdevActive = false, evdevDetail = "could not read /dev/input: ${reply ?: "no reply"}")
            }
            return
        }

        captureStartedByUs = true
        runCatching { service.setRawEventStreaming(true) }

        evdevJob = scope.launch {
            controller.privileged.rawEvents.collect { event ->
                val side = slotSides.getOrNull(event.slot) ?: return@collect
                when (event.type) {
                    LinuxInput.EV_KEY -> handleSignal(evdevEngine, side, false, event.code, event.value.toFloat())
                    LinuxInput.EV_ABS -> handleSignal(evdevEngine, side, true, event.code, event.value.toFloat())
                }
            }
        }

        val detail = "reading ${paths.size} node(s): " +
            paths.entries.joinToString { "${it.key.label}=${it.value}" }
        _state.update { it.copy(evdevActive = true, evdevDetail = detail) }
    }

    private fun handleSignal(
        engine: CalibrationEngine,
        side: JoyConSide,
        isAxis: Boolean,
        code: Int,
        value: Float,
    ) {
        if (!_state.value.active) return
        val result = synchronized(lock) {
            if (isAxis) engine.onAxis(side, code, value) else engine.onKey(side, code, value != 0f)
        }
        when (result) {
            null -> Unit
            is CaptureResult.PhaseDone -> _state.update { it.copy(phase = result.phase) }
            is CaptureResult.StepDone -> {
                val step = _state.value.currentStep
                addCapture("[${engine.domain.name}] ${result.description}")
                if (step != null && result.sourceSide != step.expectedSide) {
                    addWarning(
                        "${step.targetLabel} came from ${result.sourceSide.label} but was expected from " +
                            "${step.expectedSide.label} - check which Joy-Con is assigned to which slot.",
                    )
                }
                scheduleAdvance()
            }
        }
    }

    private fun addCapture(line: String) {
        _state.update { it.copy(captured = (listOf(line) + it.captured).take(CAPTURE_LOG_LIMIT)) }
    }

    private fun addWarning(line: String) {
        _state.update { if (line in it.warnings) it else it.copy(warnings = it.warnings + line) }
    }

    private companion object {
        /** Long enough for the second vocabulary to see the same press. */
        const val GRACE_MS = 350L
        const val CAPTURE_LOG_LIMIT = 40
    }
}
