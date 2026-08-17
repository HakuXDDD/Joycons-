package com.joymerge.quest.core

/**
 * Calibration: learn, from actual events, which code the Quest reports for each
 * Joy-Con control.
 *
 * The whole point is that we refuse to assume. Horizon OS is free to hand us
 * key codes and axis numbers that match no phone and no desktop Linux box, so
 * every binding in the final profile comes from a button the user physically
 * pressed while we were watching.
 */

enum class StepKind {
    /** Two phases: hold the stick one way, then the other. Yields an analog binding. */
    STICK_AXIS,

    /** One phase. Produces an analog binding if an axis moved, digital if a key fired. */
    TRIGGER,

    /** One phase: a single press. */
    BUTTON,
}

data class CalibrationStep(
    val id: String,
    val kind: StepKind,
    /** Which controller the user is expected to use. Bindings record the real source. */
    val expectedSide: JoyConSide,
    val prompt: String,
    val phasePrompts: List<String>,
    val axis: VirtualAxis? = null,
    val button: VirtualButton? = null,
    val optional: Boolean = false,
) {
    val phaseCount: Int get() = phasePrompts.size

    val targetLabel: String get() = axis?.label ?: button?.label ?: id
}

object CalibrationSteps {

    /**
     * The full run, in the order the user is asked to perform it.
     *
     * SL/SR and Home/Capture are optional because they are not needed for an
     * Xbox-equivalent pad and not every Joy-Con firmware reports them over
     * plain Bluetooth.
     */
    fun default(): List<CalibrationStep> = listOf(
        CalibrationStep(
            id = "left_stick_x",
            kind = StepKind.STICK_AXIS,
            expectedSide = JoyConSide.LEFT,
            prompt = "Left analog stick - horizontal",
            phasePrompts = listOf(
                "Hold the LEFT stick fully LEFT",
                "Now hold the LEFT stick fully RIGHT",
            ),
            axis = VirtualAxis.LEFT_X,
        ),
        CalibrationStep(
            id = "left_stick_y",
            kind = StepKind.STICK_AXIS,
            expectedSide = JoyConSide.LEFT,
            prompt = "Left analog stick - vertical",
            phasePrompts = listOf(
                "Hold the LEFT stick fully UP",
                "Now hold the LEFT stick fully DOWN",
            ),
            axis = VirtualAxis.LEFT_Y,
        ),
        CalibrationStep(
            id = "right_stick_x",
            kind = StepKind.STICK_AXIS,
            expectedSide = JoyConSide.RIGHT,
            prompt = "Right analog stick - horizontal",
            phasePrompts = listOf(
                "Hold the RIGHT stick fully LEFT",
                "Now hold the RIGHT stick fully RIGHT",
            ),
            axis = VirtualAxis.RIGHT_X,
        ),
        CalibrationStep(
            id = "right_stick_y",
            kind = StepKind.STICK_AXIS,
            expectedSide = JoyConSide.RIGHT,
            prompt = "Right analog stick - vertical",
            phasePrompts = listOf(
                "Hold the RIGHT stick fully UP",
                "Now hold the RIGHT stick fully DOWN",
            ),
            axis = VirtualAxis.RIGHT_Y,
        ),
        button("dpad_up", JoyConSide.LEFT, VirtualButton.DPAD_UP, "Press UP on the left Joy-Con"),
        button("dpad_down", JoyConSide.LEFT, VirtualButton.DPAD_DOWN, "Press DOWN on the left Joy-Con"),
        button("dpad_left", JoyConSide.LEFT, VirtualButton.DPAD_LEFT, "Press LEFT on the left Joy-Con"),
        button("dpad_right", JoyConSide.LEFT, VirtualButton.DPAD_RIGHT, "Press RIGHT on the left Joy-Con"),
        button("btn_a", JoyConSide.RIGHT, VirtualButton.A, "Press A on the right Joy-Con"),
        button("btn_b", JoyConSide.RIGHT, VirtualButton.B, "Press B on the right Joy-Con"),
        button("btn_x", JoyConSide.RIGHT, VirtualButton.X, "Press X on the right Joy-Con"),
        button("btn_y", JoyConSide.RIGHT, VirtualButton.Y, "Press Y on the right Joy-Con"),
        button("btn_l", JoyConSide.LEFT, VirtualButton.LB, "Press L (shoulder) on the left Joy-Con"),
        button("btn_r", JoyConSide.RIGHT, VirtualButton.RB, "Press R (shoulder) on the right Joy-Con"),
        CalibrationStep(
            id = "btn_zl",
            kind = StepKind.TRIGGER,
            expectedSide = JoyConSide.LEFT,
            prompt = "ZL becomes the left trigger",
            phasePrompts = listOf("Press and hold ZL fully"),
            axis = VirtualAxis.LEFT_TRIGGER,
        ),
        CalibrationStep(
            id = "btn_zr",
            kind = StepKind.TRIGGER,
            expectedSide = JoyConSide.RIGHT,
            prompt = "ZR becomes the right trigger",
            phasePrompts = listOf("Press and hold ZR fully"),
            axis = VirtualAxis.RIGHT_TRIGGER,
        ),
        button("btn_minus", JoyConSide.LEFT, VirtualButton.SELECT, "Press MINUS (-) on the left Joy-Con"),
        button("btn_plus", JoyConSide.RIGHT, VirtualButton.START, "Press PLUS (+) on the right Joy-Con"),
        button("btn_l3", JoyConSide.LEFT, VirtualButton.L3, "Click the LEFT stick in (L3)"),
        button("btn_r3", JoyConSide.RIGHT, VirtualButton.R3, "Click the RIGHT stick in (R3)"),
        button(
            "btn_home", JoyConSide.RIGHT, VirtualButton.MODE,
            "Optional: press HOME on the right Joy-Con", optional = true,
        ),
    )

    /**
     * SL/SR are only useful if you want them bound to something. They are kept
     * out of [default] and offered separately so the main run stays short.
     */
    fun sideButtons(): List<CalibrationStep> = listOf(
        button("btn_sl_left", JoyConSide.LEFT, VirtualButton.LB, "Optional: press SL on the left Joy-Con", true),
        button("btn_sr_left", JoyConSide.LEFT, VirtualButton.L3, "Optional: press SR on the left Joy-Con", true),
        button("btn_sl_right", JoyConSide.RIGHT, VirtualButton.RB, "Optional: press SL on the right Joy-Con", true),
        button("btn_sr_right", JoyConSide.RIGHT, VirtualButton.R3, "Optional: press SR on the right Joy-Con", true),
    )

    /** A one-off step for re-learning a single control from the mapping screen. */
    fun forButton(target: VirtualButton, side: JoyConSide): CalibrationStep = button(
        id = "relearn_${target.name.lowercase()}",
        side = side,
        target = target,
        prompt = "Press the control you want bound to ${target.label}",
    )

    /** A one-off step for re-learning a single analog output. */
    fun forAxis(target: VirtualAxis, side: JoyConSide): CalibrationStep = when (target) {
        VirtualAxis.LEFT_TRIGGER, VirtualAxis.RIGHT_TRIGGER -> CalibrationStep(
            id = "relearn_${target.name.lowercase()}",
            kind = StepKind.TRIGGER,
            expectedSide = side,
            prompt = target.label,
            phasePrompts = listOf("Press and hold the control you want bound to ${target.label}"),
            axis = target,
        )

        else -> CalibrationStep(
            id = "relearn_${target.name.lowercase()}",
            kind = StepKind.STICK_AXIS,
            expectedSide = side,
            prompt = target.label,
            phasePrompts = listOf(
                "Hold the stick fully in the negative direction for ${target.label}",
                "Now hold it fully the other way",
            ),
            axis = target,
        )
    }

    private fun button(
        id: String,
        side: JoyConSide,
        target: VirtualButton,
        prompt: String,
        optional: Boolean = false,
    ) = CalibrationStep(
        id = id,
        kind = StepKind.BUTTON,
        expectedSide = side,
        prompt = target.label,
        phasePrompts = listOf(prompt),
        button = target,
        optional = optional,
    )
}

/** Metadata for one physical axis, used to size the movement threshold. */
data class AxisInfo(val min: Float, val max: Float, val flat: Float = 0f) {
    val span: Float get() = max - min
    val center: Float get() = (max + min) / 2f
}

sealed interface CaptureResult {
    /** A phase of a multi-phase step landed; the step is not finished yet. */
    data class PhaseDone(val phase: Int, val description: String) : CaptureResult

    /** The step is finished and the profile has been updated. */
    data class StepDone(val description: String, val sourceSide: JoyConSide) : CaptureResult
}

/**
 * Captures bindings for one [SignalDomain].
 *
 * One engine handles one vocabulary. The session above may run an Android and
 * an evdev engine side by side so a single button press fills both profiles.
 *
 * Not thread safe; feed it from one thread.
 */
class CalibrationEngine(val domain: SignalDomain) {

    var profile: MappingProfile = MappingProfile(domain)
        private set

    private val axisInfo = HashMap<Long, AxisInfo>()
    private val baseline = HashMap<Long, Float>()
    private val lastValue = HashMap<Long, Float>()
    private val pressedKeys = HashSet<Long>()

    private var step: CalibrationStep? = null
    private var phase: Int = 0

    /** Phase 0 capture of a stick axis, kept until phase 1 completes the pair. */
    private var pendingAxisSide: JoyConSide? = null
    private var pendingAxisCode: Int = -1
    private var pendingAxisFirstValue: Float = 0f

    val currentPhase: Int get() = phase

    fun setAxisInfo(side: JoyConSide, code: Int, info: AxisInfo) {
        axisInfo[key(side, KIND_AXIS, code)] = info
    }

    fun loadProfile(existing: MappingProfile) {
        require(existing.domain == domain) { "profile domain ${existing.domain} != $domain" }
        profile = existing
    }

    fun reset() {
        profile = MappingProfile(domain)
        clearStep()
    }

    fun beginStep(next: CalibrationStep) {
        step = next
        phase = 0
        pendingAxisSide = null
        pendingAxisCode = -1
        // Whatever the sticks are resting at right now is the reference point.
        baseline.clear()
        baseline.putAll(lastValue)
    }

    fun clearStep() {
        step = null
        phase = 0
        pendingAxisSide = null
        pendingAxisCode = -1
    }

    /** Removes any binding the current step would have written, then ends it. */
    fun skipStep() {
        clearStep()
    }

    fun onKey(side: JoyConSide, code: Int, pressed: Boolean): CaptureResult? {
        val k = key(side, KIND_KEY, code)
        val wasPressed = k in pressedKeys
        if (pressed) pressedKeys.add(k) else pressedKeys.remove(k)
        lastValue[k] = if (pressed) 1f else 0f

        val active = step ?: return null
        // Only a fresh press counts, so a button held from the previous step is ignored.
        if (!pressed || wasPressed) return null

        val binding = DigitalBinding(side, SignalType.KEY, code)
        return when (active.kind) {
            StepKind.BUTTON -> {
                val target = active.button ?: return null
                profile = profile.withButton(target, listOf(binding))
                finish("${target.label} <- ${side.label} key $code", side)
            }

            StepKind.TRIGGER -> {
                val target = active.axis ?: return null
                profile = profile.withDigitalAxis(target, DigitalAxisBinding(positive = listOf(binding)))
                finish("${target.label} <- ${side.label} key $code (digital)", side)
            }

            StepKind.STICK_AXIS -> null // a stick phase needs an axis, not a key
        }
    }

    fun onAxis(side: JoyConSide, code: Int, value: Float): CaptureResult? {
        val k = key(side, KIND_AXIS, code)
        lastValue[k] = value
        val active = step ?: return null

        val info = axisInfo[k]
        val threshold = movementThreshold(info)
        val rest = baseline.getOrPut(k) { value }
        // Phase 1 of a stick step measures against where phase 0 left the stick,
        // not against its resting position: a stick that rests off-centre would
        // otherwise never register the second direction.
        val secondStickPhase = active.kind == StepKind.STICK_AXIS && phase == 1 &&
            side == pendingAxisSide && code == pendingAxisCode
        val reference = if (secondStickPhase) pendingAxisFirstValue else rest
        val delta = value - reference
        if (kotlin.math.abs(delta) < threshold) return null

        return when (active.kind) {
            StepKind.STICK_AXIS -> onStickAxis(active, side, code, value)

            StepKind.TRIGGER -> {
                val target = active.axis ?: return null
                val min = info?.min ?: kotlin.math.min(rest, value)
                val max = info?.max ?: kotlin.math.max(rest, value)
                val inverted = value < rest
                profile = profile.withAxis(
                    target,
                    AnalogBinding(
                        side = side,
                        code = code,
                        rawMin = min,
                        rawMax = max,
                        inverted = inverted,
                        deadzone = TRIGGER_DEADZONE,
                    ),
                )
                finish("${target.label} <- ${side.label} axis $code (analog $min..$max)", side)
            }

            StepKind.BUTTON -> {
                // Some pads report the D-pad as a hat axis rather than four keys.
                val target = active.button ?: return null
                val type = if (delta > 0) SignalType.AXIS_POSITIVE else SignalType.AXIS_NEGATIVE
                val tripPoint = reference + delta * AXIS_BUTTON_THRESHOLD_RATIO
                profile = profile.withButton(
                    target,
                    listOf(DigitalBinding(side, type, code, threshold = tripPoint)),
                )
                finish("${target.label} <- ${side.label} axis $code ${if (delta > 0) "+" else "-"}", side)
            }
        }
    }

    private fun onStickAxis(
        active: CalibrationStep,
        side: JoyConSide,
        code: Int,
        value: Float,
    ): CaptureResult? {
        val target = active.axis ?: return null
        if (phase == 0) {
            pendingAxisSide = side
            pendingAxisCode = code
            pendingAxisFirstValue = value
            phase = 1
            return CaptureResult.PhaseDone(1, "${side.label} axis $code at $value")
        }

        // Phase 1 must be the same axis moving the other way.
        if (side != pendingAxisSide || code != pendingAxisCode) return null
        val first = pendingAxisFirstValue

        val min = kotlin.math.min(first, value)
        val max = kotlin.math.max(first, value)
        // Phase 0 asked for left/up, which is the negative end of both Android
        // and Linux axes. If that produced the larger raw value, the axis runs
        // backwards relative to what we emit.
        val inverted = first > value
        profile = profile.withAxis(
            target,
            AnalogBinding(
                side = side,
                code = code,
                rawMin = min,
                rawMax = max,
                inverted = inverted,
                deadzone = STICK_DEADZONE,
            ),
        )
        return finish(
            "${target.label} <- ${side.label} axis $code range $min..$max${if (inverted) " (inverted)" else ""}",
            side,
        )
    }

    private fun finish(description: String, side: JoyConSide): CaptureResult.StepDone {
        clearStep()
        return CaptureResult.StepDone(description, side)
    }

    private fun movementThreshold(info: AxisInfo?): Float {
        if (info != null && info.span > 0f) {
            return kotlin.math.max(info.span * MOVEMENT_RATIO, info.flat * 2f)
        }
        // No range metadata (common for Android axes, which are already -1..1).
        return DEFAULT_MOVEMENT_THRESHOLD
    }

    private companion object {
        const val KIND_KEY = 0L
        const val KIND_AXIS = 1L

        /** A stick has to travel this fraction of its full range to count. */
        const val MOVEMENT_RATIO = 0.35f
        const val DEFAULT_MOVEMENT_THRESHOLD = 0.45f
        const val STICK_DEADZONE = 0.15f
        const val TRIGGER_DEADZONE = 0.05f
        const val AXIS_BUTTON_THRESHOLD_RATIO = 0.5f

        fun key(side: JoyConSide, kind: Long, code: Int): Long =
            (side.ordinal.toLong() shl 40) or (kind shl 32) or (code.toLong() and 0xFFFFFFFFL)
    }
}
