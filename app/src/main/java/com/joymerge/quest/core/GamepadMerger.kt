package com.joymerge.quest.core

/**
 * Takes raw signals from both Joy-Cons and produces one merged gamepad state.
 *
 * This is the actual "merge" in JoyMerge: the left and right controllers are
 * two independent event streams that never meet at the OS level, so we keep the
 * last known raw value of every signal from both and recompute the combined
 * pad whenever anything moves.
 *
 * Deliberately free of Android and Linux types so it can be unit tested.
 * Not thread safe — callers feed it from a single reader thread.
 */
class GamepadMerger(profile: MappingProfile) {

    var profile: MappingProfile = profile
        set(value) {
            field = value
            recompute()
        }

    /** Raw signal values, keyed by side + kind + code. */
    private val rawValues = HashMap<Long, Float>()

    var state: VirtualGamepadState = VirtualGamepadState.NEUTRAL
        private set

    /** @return true when the merged state actually changed. */
    fun onKey(side: JoyConSide, code: Int, pressed: Boolean): Boolean =
        setRaw(keyOf(side, KIND_KEY, code), if (pressed) 1f else 0f)

    /** @return true when the merged state actually changed. */
    fun onAxis(side: JoyConSide, code: Int, value: Float): Boolean =
        setRaw(keyOf(side, KIND_AXIS, code), value)

    /** Drops every remembered signal for one side, e.g. when it disconnects. */
    fun clearSide(side: JoyConSide): Boolean {
        val prefix = side.ordinal.toLong() shl 40
        val removed = rawValues.keys.filter { (it and (0xFFL shl 40)) == prefix }
        if (removed.isEmpty()) return false
        removed.forEach { rawValues.remove(it) }
        return recompute()
    }

    fun reset() {
        rawValues.clear()
        recompute()
    }

    /** Last raw value seen for a signal; used by the calibration and test UIs. */
    fun rawKey(side: JoyConSide, code: Int): Float = rawValues[keyOf(side, KIND_KEY, code)] ?: 0f

    fun rawAxis(side: JoyConSide, code: Int): Float = rawValues[keyOf(side, KIND_AXIS, code)] ?: 0f

    private fun setRaw(key: Long, value: Float): Boolean {
        val previous = rawValues.put(key, value)
        if (previous != null && previous == value) return false
        return recompute()
    }

    private fun recompute(): Boolean {
        var next = VirtualGamepadState.NEUTRAL

        for (axis in VirtualAxis.entries) {
            next = next.withAxis(axis, computeAxis(axis))
        }

        var mask = 0
        for ((button, bindings) in profile.buttons) {
            if (bindings.any { it.isActive() }) mask = mask or button.bit
        }
        next = next.copy(buttonMask = mask)

        if (next == state) return false
        state = next
        return true
    }

    private fun computeAxis(axis: VirtualAxis): Float {
        profile.axes[axis]?.let { binding ->
            val raw = rawValues[keyOf(binding.side, KIND_AXIS, binding.code)] ?: return@let
            return binding.normalize(raw, axis.bipolar)
        }
        val digital = profile.digitalAxes[axis] ?: return 0f
        val positive = digital.positive.any { it.isActive() }
        val negative = digital.negative.any { it.isActive() }
        return when {
            positive && !negative -> 1f
            negative && !positive -> if (axis.bipolar) -1f else 0f
            else -> 0f
        }
    }

    private fun DigitalBinding.isActive(): Boolean {
        val kind = if (type == SignalType.KEY) KIND_KEY else KIND_AXIS
        val raw = rawValues[keyOf(side, kind, code)] ?: return false
        return matches(raw)
    }

    private companion object {
        const val KIND_KEY = 0L
        const val KIND_AXIS = 1L

        fun keyOf(side: JoyConSide, kind: Long, code: Int): Long =
            (side.ordinal.toLong() shl 40) or (kind shl 32) or (code.toLong() and 0xFFFFFFFFL)
    }
}
