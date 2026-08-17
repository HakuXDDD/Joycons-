package com.joymerge.quest.core

/**
 * The virtual gamepad's contract, expressed independently of Android or Linux.
 *
 * Everything in this file is plain Kotlin so it can be exercised by JVM unit
 * tests without an emulator — which matters, because the merging logic is the
 * one part we *can* verify without a Quest on the desk.
 */

enum class JoyConSide {
    LEFT,
    RIGHT,
    ;

    val label: String get() = if (this == LEFT) "Joy-Con L" else "Joy-Con R"
}

/** Analog outputs of the merged pad. */
enum class VirtualAxis(val label: String, val bipolar: Boolean) {
    LEFT_X("Left Stick X", true),
    LEFT_Y("Left Stick Y", true),
    RIGHT_X("Right Stick X", true),
    RIGHT_Y("Right Stick Y", true),
    LEFT_TRIGGER("LT (analog)", false),
    RIGHT_TRIGGER("RT (analog)", false),
}

/** Digital outputs of the merged pad. D-pad included; backends decide how to emit it. */
enum class VirtualButton(val label: String) {
    A("A"),
    B("B"),
    X("X"),
    Y("Y"),
    LB("LB"),
    RB("RB"),
    L3("L3 (left stick click)"),
    R3("R3 (right stick click)"),
    START("Start / Menu"),
    SELECT("Select / View"),
    MODE("Guide / Home"),
    DPAD_UP("D-Pad Up"),
    DPAD_DOWN("D-Pad Down"),
    DPAD_LEFT("D-Pad Left"),
    DPAD_RIGHT("D-Pad Right"),
    ;

    val bit: Int get() = 1 shl ordinal
}

/**
 * One immutable snapshot of the merged pad.
 *
 * Axis values are normalised: sticks -1..1 (Y positive = down, matching both
 * Android's `AXIS_Y` and Linux `ABS_Y`), triggers 0..1.
 */
data class VirtualGamepadState(
    val leftX: Float = 0f,
    val leftY: Float = 0f,
    val rightX: Float = 0f,
    val rightY: Float = 0f,
    val leftTrigger: Float = 0f,
    val rightTrigger: Float = 0f,
    val buttonMask: Int = 0,
) {
    fun isPressed(button: VirtualButton): Boolean = (buttonMask and button.bit) != 0

    fun axis(axis: VirtualAxis): Float = when (axis) {
        VirtualAxis.LEFT_X -> leftX
        VirtualAxis.LEFT_Y -> leftY
        VirtualAxis.RIGHT_X -> rightX
        VirtualAxis.RIGHT_Y -> rightY
        VirtualAxis.LEFT_TRIGGER -> leftTrigger
        VirtualAxis.RIGHT_TRIGGER -> rightTrigger
    }

    fun withAxis(axis: VirtualAxis, value: Float): VirtualGamepadState = when (axis) {
        VirtualAxis.LEFT_X -> copy(leftX = value)
        VirtualAxis.LEFT_Y -> copy(leftY = value)
        VirtualAxis.RIGHT_X -> copy(rightX = value)
        VirtualAxis.RIGHT_Y -> copy(rightY = value)
        VirtualAxis.LEFT_TRIGGER -> copy(leftTrigger = value)
        VirtualAxis.RIGHT_TRIGGER -> copy(rightTrigger = value)
    }

    fun withButton(button: VirtualButton, pressed: Boolean): VirtualGamepadState =
        copy(buttonMask = if (pressed) buttonMask or button.bit else buttonMask and button.bit.inv())

    /** Compact wire form for the AIDL hop to the privileged process. */
    fun toFrame(): IntArray = intArrayOf(
        (leftX * FIXED).toInt(),
        (leftY * FIXED).toInt(),
        (rightX * FIXED).toInt(),
        (rightY * FIXED).toInt(),
        (leftTrigger * FIXED).toInt(),
        (rightTrigger * FIXED).toInt(),
        buttonMask,
    )

    companion object {
        const val FIXED = 10000f
        const val FRAME_SIZE = 7

        val NEUTRAL = VirtualGamepadState()

        fun fromFrame(frame: IntArray): VirtualGamepadState {
            require(frame.size >= FRAME_SIZE) { "frame too short: ${frame.size}" }
            return VirtualGamepadState(
                leftX = frame[0] / FIXED,
                leftY = frame[1] / FIXED,
                rightX = frame[2] / FIXED,
                rightY = frame[3] / FIXED,
                leftTrigger = frame[4] / FIXED,
                rightTrigger = frame[5] / FIXED,
                buttonMask = frame[6],
            )
        }
    }
}

/** Which event vocabulary a mapping profile is written in. */
enum class SignalDomain(val label: String) {
    /** `KeyEvent.getKeyCode()` / `MotionEvent` axis constants. Foreground only. */
    ANDROID("Android InputDevice events"),

    /** Raw `EV_KEY` / `EV_ABS` codes from `/dev/input/event*`. Works in background. */
    EVDEV("Raw evdev codes"),
}

/** How a digital output is driven by a physical signal. */
enum class SignalType {
    KEY,
    AXIS_POSITIVE,
    AXIS_NEGATIVE,
}

/**
 * Drives one analog output.
 *
 * [min]/[max] are the *raw* limits observed for the source axis (Android
 * reports -1..1 floats, evdev reports device-specific integers), which is what
 * lets one binding shape serve both domains.
 */
data class AnalogBinding(
    val side: JoyConSide,
    val code: Int,
    val rawMin: Float,
    val rawMax: Float,
    val inverted: Boolean = false,
    val deadzone: Float = 0.12f,
) {
    /** Raw -> -1..1 (or 0..1 for unipolar outputs). */
    fun normalize(raw: Float, bipolar: Boolean): Float {
        val span = rawMax - rawMin
        if (span == 0f) return 0f
        val unit = ((raw - rawMin) / span).coerceIn(0f, 1f)
        return if (bipolar) {
            var v = unit * 2f - 1f
            if (inverted) v = -v
            if (kotlin.math.abs(v) <= deadzone) {
                0f
            } else {
                // Rescale past the deadzone so full deflection still reaches 1.0.
                val sign = if (v < 0) -1f else 1f
                sign * ((kotlin.math.abs(v) - deadzone) / (1f - deadzone))
            }
        } else {
            val v = if (inverted) 1f - unit else unit
            if (v <= deadzone) 0f else ((v - deadzone) / (1f - deadzone))
        }
    }
}

/** Drives one digital output. A button may have several of these. */
data class DigitalBinding(
    val side: JoyConSide,
    val type: SignalType,
    val code: Int,
    /**
     * Trip point in *raw* units, not a magnitude. Storing an absolute value is
     * what lets a D-pad work whether it arrives as a hat axis centred on 0 or as
     * an axis that rests at 2048 — calibration records the midpoint between rest
     * and pressed, and the comparison below needs no knowledge of either.
     */
    val threshold: Float = 0.5f,
) {
    fun matches(rawValue: Float): Boolean = when (type) {
        SignalType.KEY -> rawValue != 0f
        SignalType.AXIS_POSITIVE -> rawValue >= threshold
        SignalType.AXIS_NEGATIVE -> rawValue <= threshold
    }
}

/**
 * Drives an analog output from digital signals.
 *
 * This is not a corner case: ZL and ZR on a Joy-Con are plain on/off buttons,
 * so LT and RT are digital far more often than they are analog.
 */
data class DigitalAxisBinding(
    val positive: List<DigitalBinding> = emptyList(),
    val negative: List<DigitalBinding> = emptyList(),
) {
    val isEmpty: Boolean get() = positive.isEmpty() && negative.isEmpty()
}

/**
 * A complete mapping for one signal domain.
 *
 * Profiles are produced by calibration, never guessed at runtime: the Quest can
 * and does report Joy-Con buttons under codes that differ from a phone.
 */
data class MappingProfile(
    val domain: SignalDomain,
    val axes: Map<VirtualAxis, AnalogBinding> = emptyMap(),
    val digitalAxes: Map<VirtualAxis, DigitalAxisBinding> = emptyMap(),
    val buttons: Map<VirtualButton, List<DigitalBinding>> = emptyMap(),
) {
    val isEmpty: Boolean get() = axes.isEmpty() && digitalAxes.isEmpty() && buttons.isEmpty()

    val boundControlCount: Int
        get() = (axes.keys + digitalAxes.filterValues { !it.isEmpty }.keys).size +
            buttons.count { it.value.isNotEmpty() }

    fun withAxis(axis: VirtualAxis, binding: AnalogBinding?): MappingProfile {
        val next = axes.toMutableMap()
        if (binding == null) next.remove(axis) else next[axis] = binding
        // An analog binding replaces any digital stand-in for the same output.
        val digital = digitalAxes.toMutableMap()
        if (binding != null) digital.remove(axis)
        return copy(axes = next, digitalAxes = digital)
    }

    fun withDigitalAxis(axis: VirtualAxis, binding: DigitalAxisBinding?): MappingProfile {
        val next = digitalAxes.toMutableMap()
        if (binding == null || binding.isEmpty) next.remove(axis) else next[axis] = binding
        val analog = axes.toMutableMap()
        if (binding != null && !binding.isEmpty) analog.remove(axis)
        return copy(axes = analog, digitalAxes = next)
    }

    fun withButton(button: VirtualButton, bindings: List<DigitalBinding>): MappingProfile {
        val next = buttons.toMutableMap()
        if (bindings.isEmpty()) next.remove(button) else next[button] = bindings
        return copy(buttons = next)
    }

    fun hasBinding(axis: VirtualAxis): Boolean =
        axes.containsKey(axis) || digitalAxes[axis]?.isEmpty == false

    fun hasBinding(button: VirtualButton): Boolean = buttons[button]?.isNotEmpty() == true

    /** Every side referenced by at least one binding. */
    fun sidesUsed(): Set<JoyConSide> = buildSet {
        axes.values.forEach { add(it.side) }
        digitalAxes.values.forEach { d ->
            d.positive.forEach { add(it.side) }
            d.negative.forEach { add(it.side) }
        }
        buttons.values.flatten().forEach { add(it.side) }
    }
}
