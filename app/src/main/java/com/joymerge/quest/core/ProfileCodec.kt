package com.joymerge.quest.core

/**
 * Serialisation for calibration results.
 *
 * A deliberately boring line-based format rather than JSON: it has no Android
 * dependency (so the round trip is covered by plain JVM tests), it survives
 * being pasted into a bug report, and an unknown line is skipped instead of
 * failing the whole file.
 */
object ProfileCodec {

    const val VERSION = 1

    data class Bundle(
        val createdAt: Long = 0L,
        val androidProfile: MappingProfile = MappingProfile(SignalDomain.ANDROID),
        val evdevProfile: MappingProfile = MappingProfile(SignalDomain.EVDEV),
        /** Stable identifier of the device assigned to each slot, if known. */
        val leftDeviceKey: String? = null,
        val rightDeviceKey: String? = null,
        val notes: List<String> = emptyList(),
    ) {
        fun profileFor(domain: SignalDomain): MappingProfile =
            if (domain == SignalDomain.ANDROID) androidProfile else evdevProfile

        fun withProfile(profile: MappingProfile): Bundle =
            if (profile.domain == SignalDomain.ANDROID) copy(androidProfile = profile)
            else copy(evdevProfile = profile)

        val isEmpty: Boolean get() = androidProfile.isEmpty && evdevProfile.isEmpty
    }

    fun encode(bundle: Bundle): String = buildString {
        appendLine("# JoyMerge Quest calibration profile")
        appendLine("version $VERSION")
        appendLine("created ${bundle.createdAt}")
        bundle.leftDeviceKey?.let { appendLine("device LEFT ${escape(it)}") }
        bundle.rightDeviceKey?.let { appendLine("device RIGHT ${escape(it)}") }
        bundle.notes.forEach { appendLine("note ${escape(it)}") }
        encodeProfile(bundle.androidProfile)
        encodeProfile(bundle.evdevProfile)
    }

    private fun StringBuilder.encodeProfile(profile: MappingProfile) {
        val d = profile.domain.name
        for ((axis, b) in profile.axes.entries.sortedBy { it.key.ordinal }) {
            appendLine(
                "axis $d ${axis.name} ${b.side.name} ${b.code} ${b.rawMin} ${b.rawMax} " +
                    "${if (b.inverted) 1 else 0} ${b.deadzone}",
            )
        }
        for ((axis, b) in profile.digitalAxes.entries.sortedBy { it.key.ordinal }) {
            b.positive.forEach { appendLine("daxis $d ${axis.name} pos ${encodeDigital(it)}") }
            b.negative.forEach { appendLine("daxis $d ${axis.name} neg ${encodeDigital(it)}") }
        }
        for ((button, list) in profile.buttons.entries.sortedBy { it.key.ordinal }) {
            list.forEach { appendLine("button $d ${button.name} ${encodeDigital(it)}") }
        }
    }

    private fun encodeDigital(b: DigitalBinding): String =
        "${b.side.name} ${b.type.name} ${b.code} ${b.threshold}"

    fun decode(text: String): Bundle {
        var bundle = Bundle()
        val notes = mutableListOf<String>()
        val axes = mutableMapOf<SignalDomain, MutableMap<VirtualAxis, AnalogBinding>>()
        val digitalAxes = mutableMapOf<SignalDomain, MutableMap<VirtualAxis, DigitalAxisBinding>>()
        val buttons = mutableMapOf<SignalDomain, MutableMap<VirtualButton, MutableList<DigitalBinding>>>()

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(' ').filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue

            when (parts[0]) {
                "created" -> parts.getOrNull(1)?.toLongOrNull()?.let { bundle = bundle.copy(createdAt = it) }

                "device" -> {
                    val side = parts.getOrNull(1)?.let { runCatching { JoyConSide.valueOf(it) }.getOrNull() }
                    val key = unescape(parts.drop(2).joinToString(" "))
                    when (side) {
                        JoyConSide.LEFT -> bundle = bundle.copy(leftDeviceKey = key)
                        JoyConSide.RIGHT -> bundle = bundle.copy(rightDeviceKey = key)
                        null -> Unit
                    }
                }

                "note" -> notes += unescape(parts.drop(1).joinToString(" "))

                "axis" -> {
                    if (parts.size < 9) continue
                    val domain = enumOrNull<SignalDomain>(parts[1]) ?: continue
                    val axis = enumOrNull<VirtualAxis>(parts[2]) ?: continue
                    val side = enumOrNull<JoyConSide>(parts[3]) ?: continue
                    val code = parts[4].toIntOrNull() ?: continue
                    val min = parts[5].toFloatOrNull() ?: continue
                    val max = parts[6].toFloatOrNull() ?: continue
                    val inverted = parts[7] == "1"
                    val deadzone = parts[8].toFloatOrNull() ?: 0.12f
                    axes.getOrPut(domain) { mutableMapOf() }[axis] =
                        AnalogBinding(side, code, min, max, inverted, deadzone)
                }

                "daxis" -> {
                    if (parts.size < 8) continue
                    val domain = enumOrNull<SignalDomain>(parts[1]) ?: continue
                    val axis = enumOrNull<VirtualAxis>(parts[2]) ?: continue
                    val polarity = parts[3]
                    val binding = decodeDigital(parts, 4) ?: continue
                    val map = digitalAxes.getOrPut(domain) { mutableMapOf() }
                    val current = map[axis] ?: DigitalAxisBinding()
                    map[axis] = if (polarity == "neg") {
                        current.copy(negative = current.negative + binding)
                    } else {
                        current.copy(positive = current.positive + binding)
                    }
                }

                "button" -> {
                    if (parts.size < 7) continue
                    val domain = enumOrNull<SignalDomain>(parts[1]) ?: continue
                    val button = enumOrNull<VirtualButton>(parts[2]) ?: continue
                    val binding = decodeDigital(parts, 3) ?: continue
                    buttons.getOrPut(domain) { mutableMapOf() }
                        .getOrPut(button) { mutableListOf() }
                        .add(binding)
                }
            }
        }

        fun build(domain: SignalDomain) = MappingProfile(
            domain = domain,
            axes = axes[domain]?.toMap() ?: emptyMap(),
            digitalAxes = digitalAxes[domain]?.toMap() ?: emptyMap(),
            buttons = buttons[domain]?.mapValues { it.value.toList() } ?: emptyMap(),
        )

        return bundle.copy(
            androidProfile = build(SignalDomain.ANDROID),
            evdevProfile = build(SignalDomain.EVDEV),
            notes = notes,
        )
    }

    private fun decodeDigital(parts: List<String>, offset: Int): DigitalBinding? {
        val side = enumOrNull<JoyConSide>(parts.getOrNull(offset) ?: return null) ?: return null
        val type = enumOrNull<SignalType>(parts.getOrNull(offset + 1) ?: return null) ?: return null
        val code = parts.getOrNull(offset + 2)?.toIntOrNull() ?: return null
        val threshold = parts.getOrNull(offset + 3)?.toFloatOrNull() ?: 0.5f
        return DigitalBinding(side, type, code, threshold)
    }

    private inline fun <reified T : Enum<T>> enumOrNull(value: String): T? =
        runCatching { enumValueOf<T>(value) }.getOrNull()

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\n", "\\n")

    private fun unescape(value: String): String =
        value.replace("\\n", "\n").replace("\\\\", "\\")
}
