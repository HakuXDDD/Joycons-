package com.joymerge.quest.nativebridge

/**
 * The subset of `linux/input-event-codes.h` this app cares about, plus
 * human-readable names so Diagnostics can print raw evdev traffic in a form a
 * person can actually read.
 *
 * Pure Kotlin on purpose — it is covered by JVM unit tests.
 */
object LinuxInput {

    // Event types
    const val EV_SYN = 0x00
    const val EV_KEY = 0x01
    const val EV_REL = 0x02
    const val EV_ABS = 0x03
    const val EV_MSC = 0x04
    const val EV_SW = 0x05
    const val EV_FF = 0x15

    const val SYN_REPORT = 0

    const val KEY_MAX = 0x2ff
    const val ABS_MAX = 0x3f

    // Bus types
    const val BUS_USB = 0x03
    const val BUS_BLUETOOTH = 0x05
    const val BUS_VIRTUAL = 0x06

    // Gamepad buttons (linux/input-event-codes.h)
    const val BTN_SOUTH = 0x130 // BTN_A
    const val BTN_EAST = 0x131 // BTN_B
    const val BTN_C = 0x132
    const val BTN_NORTH = 0x133 // BTN_X
    const val BTN_WEST = 0x134 // BTN_Y
    const val BTN_Z = 0x135
    const val BTN_TL = 0x136
    const val BTN_TR = 0x137
    const val BTN_TL2 = 0x138
    const val BTN_TR2 = 0x139
    const val BTN_SELECT = 0x13a
    const val BTN_START = 0x13b
    const val BTN_MODE = 0x13c
    const val BTN_THUMBL = 0x13d
    const val BTN_THUMBR = 0x13e

    // D-pad as buttons (how the Joy-Con L usually reports, and some drivers)
    const val BTN_DPAD_UP = 0x220
    const val BTN_DPAD_DOWN = 0x221
    const val BTN_DPAD_LEFT = 0x222
    const val BTN_DPAD_RIGHT = 0x223

    const val BTN_TRIGGER_HAPPY = 0x2c0

    // Absolute axes
    const val ABS_X = 0x00
    const val ABS_Y = 0x01
    const val ABS_Z = 0x02
    const val ABS_RX = 0x03
    const val ABS_RY = 0x04
    const val ABS_RZ = 0x05
    const val ABS_THROTTLE = 0x06
    const val ABS_RUDDER = 0x07
    const val ABS_WHEEL = 0x08
    const val ABS_GAS = 0x09
    const val ABS_BRAKE = 0x0a
    const val ABS_HAT0X = 0x10
    const val ABS_HAT0Y = 0x11

    /** Nintendo's USB/Bluetooth vendor id. */
    const val VENDOR_NINTENDO = 0x057E

    const val PRODUCT_JOYCON_LEFT = 0x2006
    const val PRODUCT_JOYCON_RIGHT = 0x2007
    const val PRODUCT_PRO_CONTROLLER = 0x2009
    const val PRODUCT_JOYCON_CHARGING_GRIP = 0x200E
    const val PRODUCT_JOYCON_COMBINED = 0x2008

    private val keyNames: Map<Int, String> = buildMap {
        put(BTN_SOUTH, "BTN_SOUTH/A")
        put(BTN_EAST, "BTN_EAST/B")
        put(BTN_C, "BTN_C")
        put(BTN_NORTH, "BTN_NORTH/X")
        put(BTN_WEST, "BTN_WEST/Y")
        put(BTN_Z, "BTN_Z")
        put(BTN_TL, "BTN_TL")
        put(BTN_TR, "BTN_TR")
        put(BTN_TL2, "BTN_TL2")
        put(BTN_TR2, "BTN_TR2")
        put(BTN_SELECT, "BTN_SELECT")
        put(BTN_START, "BTN_START")
        put(BTN_MODE, "BTN_MODE")
        put(BTN_THUMBL, "BTN_THUMBL")
        put(BTN_THUMBR, "BTN_THUMBR")
        put(BTN_DPAD_UP, "BTN_DPAD_UP")
        put(BTN_DPAD_DOWN, "BTN_DPAD_DOWN")
        put(BTN_DPAD_LEFT, "BTN_DPAD_LEFT")
        put(BTN_DPAD_RIGHT, "BTN_DPAD_RIGHT")
    }

    private val absNames: Map<Int, String> = mapOf(
        ABS_X to "ABS_X",
        ABS_Y to "ABS_Y",
        ABS_Z to "ABS_Z",
        ABS_RX to "ABS_RX",
        ABS_RY to "ABS_RY",
        ABS_RZ to "ABS_RZ",
        ABS_THROTTLE to "ABS_THROTTLE",
        ABS_RUDDER to "ABS_RUDDER",
        ABS_WHEEL to "ABS_WHEEL",
        ABS_GAS to "ABS_GAS",
        ABS_BRAKE to "ABS_BRAKE",
        ABS_HAT0X to "ABS_HAT0X",
        ABS_HAT0Y to "ABS_HAT0Y",
    )

    fun eventTypeName(type: Int): String = when (type) {
        EV_SYN -> "EV_SYN"
        EV_KEY -> "EV_KEY"
        EV_REL -> "EV_REL"
        EV_ABS -> "EV_ABS"
        EV_MSC -> "EV_MSC"
        EV_SW -> "EV_SW"
        EV_FF -> "EV_FF"
        else -> "EV_0x%02x".format(type)
    }

    fun keyName(code: Int): String = keyNames[code]
        ?: if (code in BTN_TRIGGER_HAPPY..(BTN_TRIGGER_HAPPY + 39)) {
            "BTN_TRIGGER_HAPPY${code - BTN_TRIGGER_HAPPY + 1}"
        } else {
            "KEY_0x%03x".format(code)
        }

    fun absName(code: Int): String = absNames[code] ?: "ABS_0x%02x".format(code)

    fun codeName(type: Int, code: Int): String = when (type) {
        EV_KEY -> keyName(code)
        EV_ABS -> absName(code)
        EV_SYN -> if (code == SYN_REPORT) "SYN_REPORT" else "SYN_0x%02x".format(code)
        else -> "0x%03x".format(code)
    }

    fun busName(bus: Int): String = when (bus) {
        BUS_USB -> "BUS_USB"
        BUS_BLUETOOTH -> "BUS_BLUETOOTH"
        BUS_VIRTUAL -> "BUS_VIRTUAL"
        else -> "BUS_0x%02x".format(bus)
    }

    fun describe(type: Int, code: Int, value: Int): String =
        "${eventTypeName(type)} ${codeName(type, code)} = $value"

    /** `-rw-rw----`-style rendering of a stat mode, for the Diagnostics screen. */
    fun formatMode(mode: Long): String {
        val type = when (mode.toInt() and 0xF000) {
            0x2000 -> 'c'
            0x4000 -> 'd'
            0x6000 -> 'b'
            0xA000 -> 'l'
            0x1000 -> 'p'
            0xC000 -> 's'
            else -> '-'
        }
        val bits = StringBuilder().append(type)
        val perms = "rwxrwxrwx"
        for (i in 0 until 9) {
            val bit = 1 shl (8 - i)
            bits.append(if ((mode.toInt() and bit) != 0) perms[i] else '-')
        }
        return bits.toString()
    }
}
