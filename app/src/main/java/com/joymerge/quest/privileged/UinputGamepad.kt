package com.joymerge.quest.privileged

import com.joymerge.quest.core.VirtualButton
import com.joymerge.quest.core.VirtualGamepadState
import com.joymerge.quest.nativebridge.LinuxInput
import com.joymerge.quest.nativebridge.NativeBridge

/**
 * The merged pad, published to the kernel through /dev/uinput.
 *
 * Layout is the standard Linux gamepad one that Android's `Generic.kl` already
 * understands, so the device shows up to other apps as a normal
 * `SOURCE_GAMEPAD | SOURCE_JOYSTICK` controller with no key layout file needed.
 *
 * Must run in a process that can open /dev/uinput — in practice, the Shizuku
 * shell process.
 */
class UinputGamepad {

    data class Config(
        val deviceName: String = DEFAULT_NAME,
        val busType: Int = LinuxInput.BUS_VIRTUAL,
        val vendorId: Int = DEFAULT_VENDOR,
        val productId: Int = DEFAULT_PRODUCT,
        val version: Int = 0x0100,
        /**
         * Also publish the triggers on ABS_GAS/ABS_BRAKE. Android maps ABS_Z and
         * ABS_RZ to AXIS_Z/AXIS_RZ, while some titles only read AXIS_GAS and
         * AXIS_BRAKE. Mirroring costs nothing and covers both.
         */
        val duplicateTriggerAxes: Boolean = true,
    )

    /** Everything the Diagnostics screen needs to tell the truth about this device. */
    data class Status(
        val active: Boolean,
        val fd: Int,
        val sysName: String?,
        val error: String?,
        val errno: Int,
        val config: Config?,
    )

    private var fd: Int = -1
    private var sysName: String? = null
    private var config: Config? = null
    private var lastError: String? = null
    private var lastErrno: Int = 0

    /** Last value written per (type, code), so we only emit real changes. */
    private val lastEmitted = HashMap<Int, Int>()

    private val scratch = IntArray(3 * 32)

    val isActive: Boolean get() = fd >= 0

    @Synchronized
    fun status(): Status = Status(isActive, fd, sysName, lastError, lastErrno, config)

    /**
     * Opens /dev/uinput and creates the device.
     * @return null on success, or a human readable failure with the real errno.
     */
    @Synchronized
    fun open(cfg: Config): String? {
        if (isActive) return null
        lastError = null
        lastErrno = 0

        if (!NativeBridge.isLoaded) {
            return fail(0, "native library not loaded: ${NativeBridge.loadReport}")
        }

        val opened = NativeBridge.openFd(UINPUT_PATH, writable = true, nonBlocking = false)
        if (opened < 0) {
            val message = NativeBridge.errnoMessage(opened)
            return fail(-opened, "UINPUT BLOCKED: open($UINPUT_PATH) failed: $message")
        }

        val absCodes = absLayout(cfg)
        val ranges = IntArray(absCodes.size * 4)
        absCodes.forEachIndexed { index, code ->
            val range = rangeFor(code)
            ranges[index * 4] = range[0]
            ranges[index * 4 + 1] = range[1]
            ranges[index * 4 + 2] = range[2]
            ranges[index * 4 + 3] = range[3]
        }

        val created = NativeBridge.uinputCreate(
            fd = opened,
            name = cfg.deviceName,
            busType = cfg.busType,
            vendor = cfg.vendorId,
            product = cfg.productId,
            version = cfg.version,
            keyCodes = KEY_LAYOUT,
            absCodes = absCodes,
            absRanges = ranges,
        )
        if (created < 0) {
            NativeBridge.closeFd(opened)
            val message = NativeBridge.errnoMessage(created)
            return fail(-created, "UINPUT DEVICE CREATION FAILED: $message")
        }

        fd = opened
        config = cfg
        sysName = NativeBridge.uinputSysName(opened)
        lastEmitted.clear()
        // Publish a neutral frame so the kernel has initial values for every axis.
        submit(VirtualGamepadState.NEUTRAL, force = true)
        return null
    }

    @Synchronized
    fun close() {
        if (fd >= 0) {
            NativeBridge.uinputDestroy(fd)
            NativeBridge.closeFd(fd)
        }
        fd = -1
        sysName = null
        config = null
        lastEmitted.clear()
    }

    /**
     * Writes one report. Only changed values go out, followed by SYN_REPORT.
     * @return true if anything was written.
     */
    @Synchronized
    fun submit(state: VirtualGamepadState, force: Boolean = false): Boolean {
        val currentFd = fd
        val cfg = config
        if (currentFd < 0 || cfg == null) return false

        var count = 0
        fun put(type: Int, code: Int, value: Int) {
            val key = (type shl 16) or code
            if (!force && lastEmitted[key] == value) return
            if (count + 3 > scratch.size) return
            lastEmitted[key] = value
            scratch[count++] = type
            scratch[count++] = code
            scratch[count++] = value
        }

        put(LinuxInput.EV_ABS, LinuxInput.ABS_X, stickValue(state.leftX))
        put(LinuxInput.EV_ABS, LinuxInput.ABS_Y, stickValue(state.leftY))
        put(LinuxInput.EV_ABS, LinuxInput.ABS_RX, stickValue(state.rightX))
        put(LinuxInput.EV_ABS, LinuxInput.ABS_RY, stickValue(state.rightY))
        put(LinuxInput.EV_ABS, LinuxInput.ABS_Z, triggerValue(state.leftTrigger))
        put(LinuxInput.EV_ABS, LinuxInput.ABS_RZ, triggerValue(state.rightTrigger))
        if (cfg.duplicateTriggerAxes) {
            put(LinuxInput.EV_ABS, LinuxInput.ABS_BRAKE, triggerValue(state.leftTrigger))
            put(LinuxInput.EV_ABS, LinuxInput.ABS_GAS, triggerValue(state.rightTrigger))
        }

        // The D-pad travels as a hat, which is what Android expects from a pad.
        val hatX = when {
            state.isPressed(VirtualButton.DPAD_LEFT) && !state.isPressed(VirtualButton.DPAD_RIGHT) -> -1
            state.isPressed(VirtualButton.DPAD_RIGHT) && !state.isPressed(VirtualButton.DPAD_LEFT) -> 1
            else -> 0
        }
        val hatY = when {
            state.isPressed(VirtualButton.DPAD_UP) && !state.isPressed(VirtualButton.DPAD_DOWN) -> -1
            state.isPressed(VirtualButton.DPAD_DOWN) && !state.isPressed(VirtualButton.DPAD_UP) -> 1
            else -> 0
        }
        put(LinuxInput.EV_ABS, LinuxInput.ABS_HAT0X, hatX)
        put(LinuxInput.EV_ABS, LinuxInput.ABS_HAT0Y, hatY)

        for ((button, code) in BUTTON_CODES) {
            put(LinuxInput.EV_KEY, code, if (state.isPressed(button)) 1 else 0)
        }

        if (count == 0) return false

        if (count + 3 <= scratch.size) {
            scratch[count++] = LinuxInput.EV_SYN
            scratch[count++] = LinuxInput.SYN_REPORT
            scratch[count++] = 0
        }

        val written = NativeBridge.uinputWrite(currentFd, scratch, count)
        if (written < 0) {
            lastErrno = -written
            lastError = "write to uinput failed: ${NativeBridge.errnoMessage(written)}"
            return false
        }
        return true
    }

    private fun fail(errno: Int, message: String): String {
        lastErrno = errno
        lastError = message
        return message
    }

    private fun absLayout(cfg: Config): IntArray {
        val base = intArrayOf(
            LinuxInput.ABS_X,
            LinuxInput.ABS_Y,
            LinuxInput.ABS_RX,
            LinuxInput.ABS_RY,
            LinuxInput.ABS_Z,
            LinuxInput.ABS_RZ,
            LinuxInput.ABS_HAT0X,
            LinuxInput.ABS_HAT0Y,
        )
        return if (cfg.duplicateTriggerAxes) {
            base + intArrayOf(LinuxInput.ABS_BRAKE, LinuxInput.ABS_GAS)
        } else {
            base
        }
    }

    /** @return `[min, max, fuzz, flat]`. */
    private fun rangeFor(code: Int): IntArray = when (code) {
        LinuxInput.ABS_HAT0X, LinuxInput.ABS_HAT0Y -> intArrayOf(-1, 1, 0, 0)
        LinuxInput.ABS_Z, LinuxInput.ABS_RZ, LinuxInput.ABS_GAS, LinuxInput.ABS_BRAKE ->
            intArrayOf(0, TRIGGER_MAX, 0, 0)
        // flat = 0: the deadzone was already applied when the profile normalised
        // the stick, so letting the kernel apply another one would eat travel.
        else -> intArrayOf(STICK_MIN, STICK_MAX, 0, 0)
    }

    private fun stickValue(normalized: Float): Int =
        (normalized.coerceIn(-1f, 1f) * STICK_MAX).toInt().coerceIn(STICK_MIN, STICK_MAX)

    private fun triggerValue(normalized: Float): Int =
        (normalized.coerceIn(0f, 1f) * TRIGGER_MAX).toInt().coerceIn(0, TRIGGER_MAX)

    companion object {
        const val UINPUT_PATH = "/dev/uinput"
        const val DEFAULT_NAME = "JoyMerge Virtual Gamepad"

        /** Linux Foundation vendor id; honest about being a virtual device. */
        const val DEFAULT_VENDOR = 0x1D6B
        const val DEFAULT_PRODUCT = 0x4A4D // "JM"

        const val STICK_MIN = -32768
        const val STICK_MAX = 32767
        const val TRIGGER_MAX = 255

        val BUTTON_CODES: List<Pair<VirtualButton, Int>> = listOf(
            VirtualButton.A to LinuxInput.BTN_SOUTH,
            VirtualButton.B to LinuxInput.BTN_EAST,
            VirtualButton.X to LinuxInput.BTN_NORTH,
            VirtualButton.Y to LinuxInput.BTN_WEST,
            VirtualButton.LB to LinuxInput.BTN_TL,
            VirtualButton.RB to LinuxInput.BTN_TR,
            VirtualButton.SELECT to LinuxInput.BTN_SELECT,
            VirtualButton.START to LinuxInput.BTN_START,
            VirtualButton.MODE to LinuxInput.BTN_MODE,
            VirtualButton.L3 to LinuxInput.BTN_THUMBL,
            VirtualButton.R3 to LinuxInput.BTN_THUMBR,
        )

        val KEY_LAYOUT: IntArray = BUTTON_CODES.map { it.second }.toIntArray()
    }
}
