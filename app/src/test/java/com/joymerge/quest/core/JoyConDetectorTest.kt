package com.joymerge.quest.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JoyConDetectorTest {

    @Test
    fun `nintendo ids identify each side outright`() {
        val left = JoyConDetector.classify(JoyConDetector.Candidate("whatever Horizon calls it", 0x057E, 0x2006))
        assertEquals(JoyConSide.LEFT, left.side)
        assertEquals(JoyConDetector.Confidence.HIGH, left.confidence)

        val right = JoyConDetector.classify(JoyConDetector.Candidate("", 0x057E, 0x2007))
        assertEquals(JoyConSide.RIGHT, right.side)
    }

    @Test
    fun `an already combined pair is not treated as one half`() {
        val grip = JoyConDetector.classify(JoyConDetector.Candidate("Joy-Con Charging Grip", 0x057E, 0x200E))
        assertNull(grip.side)
        assertTrue(grip.isCombinedDevice)

        val pro = JoyConDetector.classify(JoyConDetector.Candidate("Pro Controller", 0x057E, 0x2009))
        assertTrue(pro.isCombinedDevice)
    }

    @Test
    fun `names carry the side when ids are useless`() {
        assertEquals(
            JoyConSide.LEFT,
            JoyConDetector.classify(JoyConDetector.Candidate("Joy-Con (L)", 0, 0)).side,
        )
        assertEquals(
            JoyConSide.RIGHT,
            JoyConDetector.classify(JoyConDetector.Candidate("Joy-Con (R)", 0, 0)).side,
        )
        assertEquals(
            JoyConSide.LEFT,
            JoyConDetector.classify(JoyConDetector.Candidate("Nintendo Joy-Con Left", 0, 0)).side,
        )
    }

    @Test
    fun `a joycon with no side hint is reported as ambiguous rather than guessed`() {
        val verdict = JoyConDetector.classify(JoyConDetector.Candidate("Joy-Con", 0, 0))
        assertNull("we must not invent a side", verdict.side)
        assertEquals(JoyConDetector.Confidence.LOW, verdict.confidence)
        assertTrue(verdict.reason.contains("manually"))
    }

    @Test
    fun `ordinary controllers are rejected`() {
        val xbox = JoyConDetector.classify(JoyConDetector.Candidate("Xbox Wireless Controller", 0x045E, 0x02FD))
        assertNull(xbox.side)
        assertFalse(xbox.isJoyCon)
        assertEquals(JoyConDetector.Confidence.NONE, xbox.confidence)
    }

    @Test
    fun `an unknown nintendo product asks for manual assignment`() {
        val verdict = JoyConDetector.classify(JoyConDetector.Candidate("Some New Pad", 0x057E, 0x9999))
        assertNull(verdict.side)
        assertEquals(JoyConDetector.Confidence.LOW, verdict.confidence)
    }

    @Test
    fun `assignment picks the highest confidence device per side`() {
        data class Device(val name: String, val vendor: Int, val product: Int)

        val devices = listOf(
            Device("Joy-Con (L)", 0, 0), // name only -> MEDIUM
            Device("Bluetooth Joy-Con", 0x057E, 0x2006), // ids -> HIGH
            Device("Joy-Con (R)", 0x057E, 0x2007),
            Device("Quest Touch Controller", 0x2833, 0x0137),
        )

        val assigned = JoyConDetector.assignSides(devices) {
            JoyConDetector.Candidate(it.name, it.vendor, it.product)
        }

        assertEquals("Bluetooth Joy-Con", assigned[JoyConSide.LEFT]?.name)
        assertEquals("Joy-Con (R)", assigned[JoyConSide.RIGHT]?.name)
        assertEquals(2, assigned.size)
    }
}
