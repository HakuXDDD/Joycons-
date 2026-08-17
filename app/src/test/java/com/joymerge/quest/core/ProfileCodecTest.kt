package com.joymerge.quest.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCodecTest {

    private fun sampleBundle(): ProfileCodec.Bundle = ProfileCodec.Bundle(
        createdAt = 1_700_000_000_000L,
        androidProfile = MappingProfile(
            domain = SignalDomain.ANDROID,
            axes = mapOf(
                VirtualAxis.LEFT_X to AnalogBinding(JoyConSide.LEFT, 0, -1f, 1f, deadzone = 0.13f),
            ),
            buttons = mapOf(
                VirtualButton.A to listOf(DigitalBinding(JoyConSide.RIGHT, SignalType.KEY, 96)),
            ),
        ),
        evdevProfile = MappingProfile(
            domain = SignalDomain.EVDEV,
            axes = mapOf(
                VirtualAxis.RIGHT_Y to AnalogBinding(JoyConSide.RIGHT, 4, 0f, 4095f, inverted = true),
            ),
            digitalAxes = mapOf(
                VirtualAxis.LEFT_TRIGGER to DigitalAxisBinding(
                    positive = listOf(DigitalBinding(JoyConSide.LEFT, SignalType.KEY, 312)),
                ),
            ),
            buttons = mapOf(
                VirtualButton.DPAD_UP to listOf(
                    DigitalBinding(JoyConSide.LEFT, SignalType.AXIS_NEGATIVE, 0x11, threshold = -0.5f),
                ),
                VirtualButton.START to listOf(
                    DigitalBinding(JoyConSide.RIGHT, SignalType.KEY, 315),
                    DigitalBinding(JoyConSide.LEFT, SignalType.KEY, 316),
                ),
            ),
        ),
        leftDeviceKey = "/dev/input/event7",
        rightDeviceKey = "/dev/input/event8",
        notes = listOf("captured on a Quest 2"),
    )

    @Test
    fun `round trip preserves every binding`() {
        val original = sampleBundle()
        val restored = ProfileCodec.decode(ProfileCodec.encode(original))

        assertEquals(original.createdAt, restored.createdAt)
        assertEquals(original.leftDeviceKey, restored.leftDeviceKey)
        assertEquals(original.rightDeviceKey, restored.rightDeviceKey)
        assertEquals(original.notes, restored.notes)
        assertEquals(original.androidProfile, restored.androidProfile)
        assertEquals(original.evdevProfile, restored.evdevProfile)
    }

    @Test
    fun `the two domains stay separate`() {
        val restored = ProfileCodec.decode(ProfileCodec.encode(sampleBundle()))

        assertEquals(SignalDomain.ANDROID, restored.androidProfile.domain)
        assertEquals(SignalDomain.EVDEV, restored.evdevProfile.domain)
        assertTrue("the Android profile must not pick up evdev bindings", restored.androidProfile.axes.size == 1)
        assertNotNull(restored.evdevProfile.digitalAxes[VirtualAxis.LEFT_TRIGGER])
    }

    @Test
    fun `several bindings on one button survive`() {
        val restored = ProfileCodec.decode(ProfileCodec.encode(sampleBundle()))
        assertEquals(2, restored.evdevProfile.buttons[VirtualButton.START]?.size)
    }

    @Test
    fun `garbage lines are skipped instead of failing the file`() {
        val text = ProfileCodec.encode(sampleBundle()) + """
            this is not a real line
            axis EVDEV NOT_AN_AXIS LEFT 0 0 1 0 0.1
            button EVDEV A
        """.trimIndent()

        val restored = ProfileCodec.decode(text)
        assertEquals(sampleBundle().evdevProfile, restored.evdevProfile)
    }

    @Test
    fun `an empty document decodes to an empty bundle`() {
        val restored = ProfileCodec.decode("")
        assertTrue(restored.isEmpty)
    }

    @Test
    fun `the encoded form is human readable`() {
        val text = ProfileCodec.encode(sampleBundle())
        assertTrue("expected a comment header", text.startsWith("#"))
        assertTrue("expected named enums, not ordinals", text.contains("LEFT_TRIGGER"))
        assertTrue(text.contains("device LEFT /dev/input/event7"))
    }
}
