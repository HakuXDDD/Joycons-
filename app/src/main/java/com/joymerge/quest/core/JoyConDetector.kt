package com.joymerge.quest.core

import java.util.Locale

/**
 * Works out which physical device is the left Joy-Con and which is the right.
 *
 * Horizon OS does not necessarily expose the same names a phone does, so name
 * matching is only the last resort. VID/PID is checked first, then several name
 * shapes, and every verdict carries the reason that produced it so the
 * Diagnostics screen can show *why* a device was or was not picked.
 */
object JoyConDetector {

    enum class Confidence { HIGH, MEDIUM, LOW, NONE }

    /** A device as seen either through Android's InputDevice or raw evdev. */
    data class Candidate(
        val name: String,
        val vendorId: Int,
        val productId: Int,
        val uniq: String? = null,
        /** Codes the device advertises; used to break ties when ids are useless. */
        val keyCodes: List<Int> = emptyList(),
        val axisCodes: List<Int> = emptyList(),
    )

    data class Verdict(
        val side: JoyConSide?,
        val confidence: Confidence,
        val reason: String,
        /** True for a Pro Controller or a pair already merged by the OS. */
        val isCombinedDevice: Boolean = false,
    ) {
        val isJoyCon: Boolean get() = side != null
    }

    private val NOT_A_JOYCON = Verdict(null, Confidence.NONE, "no Joy-Con signal in id or name")

    fun classify(candidate: Candidate): Verdict {
        val name = candidate.name.lowercase(Locale.ROOT)

        if (candidate.vendorId == LinuxInputIds.VENDOR_NINTENDO) {
            when (candidate.productId) {
                LinuxInputIds.PRODUCT_JOYCON_LEFT ->
                    return Verdict(JoyConSide.LEFT, Confidence.HIGH, "Nintendo VID + PID 0x2006 (Joy-Con L)")

                LinuxInputIds.PRODUCT_JOYCON_RIGHT ->
                    return Verdict(JoyConSide.RIGHT, Confidence.HIGH, "Nintendo VID + PID 0x2007 (Joy-Con R)")

                LinuxInputIds.PRODUCT_JOYCON_COMBINED,
                LinuxInputIds.PRODUCT_JOYCON_CHARGING_GRIP,
                    -> return Verdict(
                    null, Confidence.HIGH,
                    "Nintendo VID + PID 0x%04x - already a combined Joy-Con device".format(candidate.productId),
                    isCombinedDevice = true,
                )

                LinuxInputIds.PRODUCT_PRO_CONTROLLER ->
                    return Verdict(
                        null, Confidence.HIGH,
                        "Nintendo VID + PID 0x2009 (Pro Controller) - already a full pad",
                        isCombinedDevice = true,
                    )
            }
        }

        // Names Horizon OS / the Linux hid-nintendo driver / stock Bluetooth use.
        val looksLikeJoyCon = JOYCON_NAME_HINTS.any { it in name }
        if (looksLikeJoyCon) {
            sideFromName(name)?.let { side ->
                val confidence =
                    if (candidate.vendorId == LinuxInputIds.VENDOR_NINTENDO) Confidence.HIGH else Confidence.MEDIUM
                return Verdict(side, confidence, "name \"${candidate.name}\" indicates ${side.label}")
            }
            return Verdict(
                null, Confidence.LOW,
                "name \"${candidate.name}\" looks like a Joy-Con but gives no side - assign it manually",
            )
        }

        if (candidate.vendorId == LinuxInputIds.VENDOR_NINTENDO) {
            return Verdict(
                null, Confidence.LOW,
                "Nintendo VID with unknown PID 0x%04x - assign it manually".format(candidate.productId),
            )
        }

        return NOT_A_JOYCON
    }

    private fun sideFromName(lowercaseName: String): JoyConSide? {
        LEFT_PATTERNS.forEach { if (it.containsMatchIn(lowercaseName)) return JoyConSide.LEFT }
        RIGHT_PATTERNS.forEach { if (it.containsMatchIn(lowercaseName)) return JoyConSide.RIGHT }
        return null
    }

    /**
     * Picks the best device for each side out of everything on the system.
     *
     * When two devices claim the same side, the higher-confidence one wins; ties
     * keep the first, which is the order Android/evdev enumerated them in.
     */
    fun <T> assignSides(
        items: List<T>,
        toCandidate: (T) -> Candidate,
    ): Map<JoyConSide, T> {
        val best = HashMap<JoyConSide, Pair<T, Confidence>>()
        for (item in items) {
            val verdict = classify(toCandidate(item))
            val side = verdict.side ?: continue
            val current = best[side]
            if (current == null || verdict.confidence.ordinal < current.second.ordinal) {
                best[side] = item to verdict.confidence
            }
        }
        return best.mapValues { it.value.first }
    }

    private val JOYCON_NAME_HINTS = listOf("joy-con", "joycon", "joy con", "joy_con")

    private val LEFT_PATTERNS = listOf(
        Regex("""\(l\)"""),
        Regex("""\bl\b"""),
        Regex("""left"""),
        Regex("""joy-?_?con\s*l\b"""),
    )

    private val RIGHT_PATTERNS = listOf(
        Regex("""\(r\)"""),
        Regex("""\br\b"""),
        Regex("""right"""),
        Regex("""joy-?_?con\s*r\b"""),
    )
}

/** Vendor/product ids kept separate so this file stays free of Android imports. */
object LinuxInputIds {
    const val VENDOR_NINTENDO = 0x057E
    const val PRODUCT_JOYCON_LEFT = 0x2006
    const val PRODUCT_JOYCON_RIGHT = 0x2007
    const val PRODUCT_JOYCON_COMBINED = 0x2008
    const val PRODUCT_PRO_CONTROLLER = 0x2009
    const val PRODUCT_JOYCON_CHARGING_GRIP = 0x200E
}
