package com.joymerge.quest.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationEngineTest {

    private fun engine(): CalibrationEngine = CalibrationEngine(SignalDomain.EVDEV).apply {
        // A typical Joy-Con stick as the kernel reports it.
        setAxisInfo(JoyConSide.LEFT, 0, AxisInfo(0f, 4095f, flat = 250f))
        setAxisInfo(JoyConSide.LEFT, 1, AxisInfo(0f, 4095f, flat = 250f))
    }

    private val leftStickX = CalibrationSteps.default().first { it.id == "left_stick_x" }
    private val buttonA = CalibrationSteps.default().first { it.id == "btn_a" }
    private val zl = CalibrationSteps.default().first { it.id == "btn_zl" }

    @Test
    fun `a stick step needs both directions before it binds`() {
        val engine = engine()
        engine.onAxis(JoyConSide.LEFT, 0, 2048f) // resting
        engine.beginStep(leftStickX)

        val first = engine.onAxis(JoyConSide.LEFT, 0, 0f)
        assertTrue("holding left completes phase 0", first is CaptureResult.PhaseDone)
        assertTrue("nothing is bound yet", engine.profile.axes.isEmpty())

        val second = engine.onAxis(JoyConSide.LEFT, 0, 4095f)
        assertTrue("holding right finishes the step", second is CaptureResult.StepDone)

        val binding = engine.profile.axes[VirtualAxis.LEFT_X]
        assertNotNull(binding)
        assertEquals(0f, binding!!.rawMin, 0.001f)
        assertEquals(4095f, binding.rawMax, 0.001f)
        assertEquals("left first means the axis runs the normal way", false, binding.inverted)
    }

    @Test
    fun `a stick that reports backwards is marked inverted`() {
        val engine = engine()
        engine.onAxis(JoyConSide.LEFT, 0, 2048f)
        engine.beginStep(leftStickX)

        // This pad reports its maximum when pushed left.
        engine.onAxis(JoyConSide.LEFT, 0, 4095f)
        engine.onAxis(JoyConSide.LEFT, 0, 0f)

        assertEquals(true, engine.profile.axes[VirtualAxis.LEFT_X]!!.inverted)
    }

    @Test
    fun `a stick resting off centre still completes`() {
        val engine = engine()
        // Rest sits near one end, which used to defeat the second phase.
        engine.onAxis(JoyConSide.LEFT, 0, 300f)
        engine.beginStep(leftStickX)

        assertTrue(engine.onAxis(JoyConSide.LEFT, 0, 4095f) is CaptureResult.PhaseDone)
        assertTrue(engine.onAxis(JoyConSide.LEFT, 0, 0f) is CaptureResult.StepDone)
        assertNotNull(engine.profile.axes[VirtualAxis.LEFT_X])
    }

    @Test
    fun `small stick jitter does not trigger a capture`() {
        val engine = engine()
        engine.onAxis(JoyConSide.LEFT, 0, 2048f)
        engine.beginStep(leftStickX)

        assertNull(engine.onAxis(JoyConSide.LEFT, 0, 2100f))
        assertNull(engine.onAxis(JoyConSide.LEFT, 0, 1990f))
        assertEquals(0, engine.currentPhase)
    }

    @Test
    fun `a button step binds the first fresh press`() {
        val engine = engine()
        engine.beginStep(buttonA)

        val result = engine.onKey(JoyConSide.RIGHT, 304, true)
        assertTrue(result is CaptureResult.StepDone)
        assertEquals(JoyConSide.RIGHT, (result as CaptureResult.StepDone).sourceSide)

        val binding = engine.profile.buttons[VirtualButton.A]?.single()
        assertEquals(304, binding?.code)
        assertEquals(SignalType.KEY, binding?.type)
    }

    @Test
    fun `a button held over from the previous step is ignored`() {
        val engine = engine()
        engine.onKey(JoyConSide.RIGHT, 304, true) // still down from before
        engine.beginStep(buttonA)

        assertNull("a held button is not a fresh press", engine.onKey(JoyConSide.RIGHT, 304, true))

        engine.onKey(JoyConSide.RIGHT, 304, false)
        assertTrue(engine.onKey(JoyConSide.RIGHT, 304, true) is CaptureResult.StepDone)
    }

    @Test
    fun `a digital ZL becomes a digital trigger binding`() {
        val engine = engine()
        engine.beginStep(zl)

        assertTrue(engine.onKey(JoyConSide.LEFT, 312, true) is CaptureResult.StepDone)

        assertTrue("no analog binding for a plain button", engine.profile.axes[VirtualAxis.LEFT_TRIGGER] == null)
        val digital = engine.profile.digitalAxes[VirtualAxis.LEFT_TRIGGER]
        assertNotNull(digital)
        assertEquals(312, digital!!.positive.single().code)
    }

    @Test
    fun `an analog trigger becomes an analog binding`() {
        val engine = engine()
        engine.setAxisInfo(JoyConSide.LEFT, 2, AxisInfo(0f, 255f))
        engine.onAxis(JoyConSide.LEFT, 2, 0f)
        engine.beginStep(zl)

        assertTrue(engine.onAxis(JoyConSide.LEFT, 2, 255f) is CaptureResult.StepDone)

        val binding = engine.profile.axes[VirtualAxis.LEFT_TRIGGER]
        assertNotNull(binding)
        assertEquals(255f, binding!!.rawMax, 0.001f)
        assertTrue(engine.profile.digitalAxes[VirtualAxis.LEFT_TRIGGER] == null)
    }

    @Test
    fun `an axis-driven dpad records a trip point between rest and pressed`() {
        val engine = CalibrationEngine(SignalDomain.EVDEV)
        engine.setAxisInfo(JoyConSide.LEFT, 0x10, AxisInfo(-1f, 1f))
        engine.onAxis(JoyConSide.LEFT, 0x10, 0f)
        val step = CalibrationSteps.default().first { it.id == "dpad_left" }
        engine.beginStep(step)

        assertTrue(engine.onAxis(JoyConSide.LEFT, 0x10, -1f) is CaptureResult.StepDone)

        val binding = engine.profile.buttons[VirtualButton.DPAD_LEFT]!!.single()
        assertEquals(SignalType.AXIS_NEGATIVE, binding.type)
        assertEquals(-0.5f, binding.threshold, 0.001f)
        assertTrue("pressed reads as pressed", binding.matches(-1f))
        assertTrue("rest does not", !binding.matches(0f))
    }

    @Test
    fun `skipping a step leaves the profile untouched`() {
        val engine = engine()
        engine.beginStep(buttonA)
        engine.skipStep()

        assertNull(engine.onKey(JoyConSide.RIGHT, 304, true))
        assertTrue(engine.profile.isEmpty)
    }

    @Test
    fun `loading a profile keeps existing bindings while relearning one control`() {
        val existing = MappingProfile(
            domain = SignalDomain.EVDEV,
            buttons = mapOf(
                VirtualButton.B to listOf(DigitalBinding(JoyConSide.RIGHT, SignalType.KEY, 305)),
            ),
        )
        val engine = CalibrationEngine(SignalDomain.EVDEV)
        engine.loadProfile(existing)
        engine.beginStep(buttonA)
        engine.onKey(JoyConSide.RIGHT, 304, true)

        assertEquals(304, engine.profile.buttons[VirtualButton.A]?.single()?.code)
        assertEquals("B survived the single-control relearn", 305, engine.profile.buttons[VirtualButton.B]?.single()?.code)
    }
}
