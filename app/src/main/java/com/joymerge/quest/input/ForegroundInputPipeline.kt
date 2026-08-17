package com.joymerge.quest.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.joymerge.quest.core.GamepadMerger
import com.joymerge.quest.core.JoyConSide
import com.joymerge.quest.core.MappingProfile
import com.joymerge.quest.core.SignalDomain
import com.joymerge.quest.core.VirtualGamepadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One observed input, kept for the Diagnostics "last events" panel. */
data class InputTrace(
    val side: JoyConSide,
    val isAxis: Boolean,
    val code: Int,
    val value: Float,
    val label: String,
    val at: Long = System.currentTimeMillis(),
)

/**
 * Turns the events an Activity receives into merged gamepad state.
 *
 * This is the foreground path. It is what calibration and the Gamepad Tester
 * use, and it works with no privileges whatsoever — but only while JoyMerge is
 * the focused app, which is exactly why the privileged evdev path exists for
 * real play.
 */
class ForegroundInputPipeline {

    private val merger = GamepadMerger(MappingProfile(SignalDomain.ANDROID))

    /** Android device id -> which Joy-Con slot it fills. */
    @Volatile
    var assignment: Map<Int, JoyConSide> = emptyMap()

    /** Last raw value seen per (device, axis), so we only report real movement. */
    private val lastAxisValues = HashMap<Long, Float>()

    private val _state = MutableStateFlow(VirtualGamepadState.NEUTRAL)
    val state: StateFlow<VirtualGamepadState> = _state.asStateFlow()

    private val _traces = MutableStateFlow<List<InputTrace>>(emptyList())
    val traces: StateFlow<List<InputTrace>> = _traces.asStateFlow()

    /** Calibration listens here to learn which code belongs to which control. */
    @Volatile
    var signalListener: ((side: JoyConSide, isAxis: Boolean, code: Int, value: Float) -> Unit)? = null

    var profile: MappingProfile
        get() = merger.profile
        set(value) {
            require(value.domain == SignalDomain.ANDROID) { "foreground pipeline needs an ANDROID profile" }
            merger.profile = value
            _state.value = merger.state
        }

    fun reset() {
        lastAxisValues.clear()
        merger.reset()
        _state.value = merger.state
        _traces.value = emptyList()
    }

    /** @return true when the event belonged to an assigned Joy-Con and was consumed. */
    fun handleKey(event: KeyEvent): Boolean {
        val side = assignment[event.deviceId] ?: return false
        // Never swallow navigation keys. If a device is assigned to a slot by
        // mistake, the user must still be able to back out of the screen.
        if (event.keyCode in NAVIGATION_KEYS) return false
        if (event.repeatCount > 0) return true

        val pressed = event.action == KeyEvent.ACTION_DOWN
        record(InputTrace(side, false, event.keyCode, if (pressed) 1f else 0f, KeyEvent.keyCodeToString(event.keyCode)))
        signalListener?.invoke(side, false, event.keyCode, if (pressed) 1f else 0f)

        if (merger.onKey(side, event.keyCode, pressed)) {
            _state.value = merger.state
        }
        return true
    }

    /** @return true when the event belonged to an assigned Joy-Con and was consumed. */
    fun handleMotion(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_CLASS_JOYSTICK != InputDevice.SOURCE_CLASS_JOYSTICK) return false
        val side = assignment[event.deviceId] ?: return false

        val ranges = event.device?.motionRanges
        val axes = if (ranges.isNullOrEmpty()) {
            InputDeviceManager.PROBE_AXES
        } else {
            ranges.map { it.axis }.distinct()
        }

        var changed = false
        for (axis in axes) {
            val value = event.getAxisValue(axis)
            val key = (event.deviceId.toLong() shl 32) or (axis.toLong() and 0xFFFFFFFFL)
            val previous = lastAxisValues[key]
            if (previous != null && kotlin.math.abs(previous - value) < AXIS_EPSILON) continue
            lastAxisValues[key] = value

            record(InputTrace(side, true, axis, value, MotionEvent.axisToString(axis)))
            signalListener?.invoke(side, true, axis, value)
            if (merger.onAxis(side, axis, value)) changed = true
        }
        if (changed) _state.value = merger.state
        return true
    }

    /** Drops a side's signals when its controller disconnects. */
    fun clearSide(side: JoyConSide) {
        if (merger.clearSide(side)) _state.value = merger.state
    }

    private fun record(trace: InputTrace) {
        val current = _traces.value
        val next = ArrayList<InputTrace>(minOf(current.size + 1, TRACE_CAPACITY))
        next.add(trace)
        next.addAll(current.take(TRACE_CAPACITY - 1))
        _traces.value = next
    }

    private companion object {
        const val AXIS_EPSILON = 0.004f
        const val TRACE_CAPACITY = 60

        val NAVIGATION_KEYS = setOf(
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_POWER,
        )
    }
}
