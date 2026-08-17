package com.joymerge.quest.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The merge is the part of this app that can be verified without a Quest, so
 * it is verified thoroughly.
 */
class GamepadMergerTest {

    /** A profile shaped like a real Joy-Con pair read over evdev. */
    private fun joyConProfile() = MappingProfile(
        domain = SignalDomain.EVDEV,
        axes = mapOf(
            VirtualAxis.LEFT_X to AnalogBinding(JoyConSide.LEFT, 0, 0f, 4095f, deadzone = 0.1f),
            VirtualAxis.LEFT_Y to AnalogBinding(JoyConSide.LEFT, 1, 0f, 4095f, deadzone = 0.1f),
            VirtualAxis.RIGHT_X to AnalogBinding(JoyConSide.RIGHT, 3, 0f, 4095f, deadzone = 0.1f),
            VirtualAxis.RIGHT_Y to AnalogBinding(JoyConSide.RIGHT, 4, 0f, 4095f, deadzone = 0.1f),
        ),
        digitalAxes = mapOf(
            // ZL and ZR are plain buttons on a Joy-Con, so the triggers are digital.
            VirtualAxis.LEFT_TRIGGER to DigitalAxisBinding(
                positive = listOf(DigitalBinding(JoyConSide.LEFT, SignalType.KEY, 312)),
            ),
            VirtualAxis.RIGHT_TRIGGER to DigitalAxisBinding(
                positive = listOf(DigitalBinding(JoyConSide.RIGHT, SignalType.KEY, 313)),
            ),
        ),
        buttons = mapOf(
            VirtualButton.A to listOf(DigitalBinding(JoyConSide.RIGHT, SignalType.KEY, 304)),
            VirtualButton.B to listOf(DigitalBinding(JoyConSide.RIGHT, SignalType.KEY, 305)),
            VirtualButton.DPAD_LEFT to listOf(DigitalBinding(JoyConSide.LEFT, SignalType.KEY, 546)),
            VirtualButton.LB to listOf(DigitalBinding(JoyConSide.LEFT, SignalType.KEY, 310)),
            VirtualButton.SELECT to listOf(DigitalBinding(JoyConSide.LEFT, SignalType.KEY, 314)),
            VirtualButton.START to listOf(DigitalBinding(JoyConSide.RIGHT, SignalType.KEY, 315)),
        ),
    )

    @Test
    fun `both joycons contribute to a single state`() {
        val merger = GamepadMerger(joyConProfile())

        merger.onAxis(JoyConSide.LEFT, 0, 4095f)
        merger.onKey(JoyConSide.RIGHT, 304, true)
        merger.onKey(JoyConSide.LEFT, 312, true)

        val state = merger.state
        assertEquals(1f, state.leftX, 0.001f)
        assertTrue("A comes from the right Joy-Con", state.isPressed(VirtualButton.A))
        assertEquals("ZL drives LT to full", 1f, state.leftTrigger, 0.001f)
        // The right stick was never touched, so it must stay centred.
        assertEquals(0f, state.rightX, 0.001f)
    }

    @Test
    fun `stick centre lands inside the deadzone`() {
        val merger = GamepadMerger(joyConProfile())
        merger.onAxis(JoyConSide.LEFT, 0, 2047.5f)
        assertEquals(0f, merger.state.leftX, 0.0001f)
    }

    @Test
    fun `stick extremes reach full deflection despite the deadzone`() {
        val merger = GamepadMerger(joyConProfile())

        merger.onAxis(JoyConSide.LEFT, 0, 0f)
        assertEquals(-1f, merger.state.leftX, 0.001f)

        merger.onAxis(JoyConSide.LEFT, 0, 4095f)
        assertEquals(1f, merger.state.leftX, 0.001f)
    }

    @Test
    fun `inverted binding flips the sign`() {
        val profile = MappingProfile(
            domain = SignalDomain.EVDEV,
            axes = mapOf(
                VirtualAxis.LEFT_Y to AnalogBinding(JoyConSide.LEFT, 1, 0f, 4095f, inverted = true, deadzone = 0.1f),
            ),
        )
        val merger = GamepadMerger(profile)
        merger.onAxis(JoyConSide.LEFT, 1, 4095f)
        assertEquals(-1f, merger.state.leftY, 0.001f)
    }

    @Test
    fun `releasing a button clears only that bit`() {
        val merger = GamepadMerger(joyConProfile())
        merger.onKey(JoyConSide.RIGHT, 304, true)
        merger.onKey(JoyConSide.RIGHT, 305, true)
        assertTrue(merger.state.isPressed(VirtualButton.A))
        assertTrue(merger.state.isPressed(VirtualButton.B))

        merger.onKey(JoyConSide.RIGHT, 304, false)
        assertFalse(merger.state.isPressed(VirtualButton.A))
        assertTrue(merger.state.isPressed(VirtualButton.B))
    }

    @Test
    fun `a disconnected joycon only clears its own half`() {
        val merger = GamepadMerger(joyConProfile())
        merger.onAxis(JoyConSide.LEFT, 0, 4095f)
        merger.onKey(JoyConSide.RIGHT, 304, true)

        merger.clearSide(JoyConSide.LEFT)

        assertEquals("left stick returns to centre", 0f, merger.state.leftX, 0.001f)
        assertTrue("the right Joy-Con keeps working", merger.state.isPressed(VirtualButton.A))
    }

    @Test
    fun `repeating the same value reports no change`() {
        val merger = GamepadMerger(joyConProfile())
        assertTrue(merger.onKey(JoyConSide.RIGHT, 304, true))
        assertFalse("a duplicate press must not churn the backend", merger.onKey(JoyConSide.RIGHT, 304, true))
    }

    @Test
    fun `unbound signals are ignored`() {
        val merger = GamepadMerger(joyConProfile())
        assertFalse(merger.onKey(JoyConSide.RIGHT, 999, true))
        assertEquals(0, merger.state.buttonMask)
    }

    @Test
    fun `a hat axis can drive a dpad button`() {
        val profile = MappingProfile(
            domain = SignalDomain.EVDEV,
            buttons = mapOf(
                VirtualButton.DPAD_LEFT to listOf(
                    DigitalBinding(JoyConSide.LEFT, SignalType.AXIS_NEGATIVE, 0x10, threshold = -0.5f),
                ),
                VirtualButton.DPAD_RIGHT to listOf(
                    DigitalBinding(JoyConSide.LEFT, SignalType.AXIS_POSITIVE, 0x10, threshold = 0.5f),
                ),
            ),
        )
        val merger = GamepadMerger(profile)

        merger.onAxis(JoyConSide.LEFT, 0x10, -1f)
        assertTrue(merger.state.isPressed(VirtualButton.DPAD_LEFT))
        assertFalse(merger.state.isPressed(VirtualButton.DPAD_RIGHT))

        merger.onAxis(JoyConSide.LEFT, 0x10, 1f)
        assertFalse(merger.state.isPressed(VirtualButton.DPAD_LEFT))
        assertTrue(merger.state.isPressed(VirtualButton.DPAD_RIGHT))

        merger.onAxis(JoyConSide.LEFT, 0x10, 0f)
        assertEquals(0, merger.state.buttonMask)
    }

    @Test
    fun `an off-centre axis still works as a button`() {
        // A D-pad reported as an axis resting at 2048 and pressing to 4095.
        val profile = MappingProfile(
            domain = SignalDomain.EVDEV,
            buttons = mapOf(
                VirtualButton.DPAD_UP to listOf(
                    DigitalBinding(JoyConSide.LEFT, SignalType.AXIS_POSITIVE, 1, threshold = 3071f),
                ),
            ),
        )
        val merger = GamepadMerger(profile)

        merger.onAxis(JoyConSide.LEFT, 1, 2048f)
        assertFalse("resting position must not read as pressed", merger.state.isPressed(VirtualButton.DPAD_UP))

        merger.onAxis(JoyConSide.LEFT, 1, 4095f)
        assertTrue(merger.state.isPressed(VirtualButton.DPAD_UP))
    }

    @Test
    fun `frame round trip preserves the state`() {
        val original = VirtualGamepadState(
            leftX = -0.5f,
            leftY = 0.25f,
            rightX = 1f,
            rightY = -1f,
            leftTrigger = 0.75f,
            rightTrigger = 0.125f,
            buttonMask = VirtualButton.A.bit or VirtualButton.START.bit,
        )
        val restored = VirtualGamepadState.fromFrame(original.toFrame())

        assertEquals(original.leftX, restored.leftX, 0.0002f)
        assertEquals(original.leftY, restored.leftY, 0.0002f)
        assertEquals(original.rightX, restored.rightX, 0.0002f)
        assertEquals(original.rightY, restored.rightY, 0.0002f)
        assertEquals(original.leftTrigger, restored.leftTrigger, 0.0002f)
        assertEquals(original.rightTrigger, restored.rightTrigger, 0.0002f)
        assertEquals(original.buttonMask, restored.buttonMask)
    }

    @Test
    fun `changing the profile recomputes from the signals already seen`() {
        val merger = GamepadMerger(joyConProfile())
        merger.onKey(JoyConSide.RIGHT, 304, true)
        assertTrue(merger.state.isPressed(VirtualButton.A))

        merger.profile = joyConProfile().withButton(
            VirtualButton.X,
            listOf(DigitalBinding(JoyConSide.RIGHT, SignalType.KEY, 304)),
        )

        assertTrue("the held button now drives X as well", merger.state.isPressed(VirtualButton.X))
    }
}
