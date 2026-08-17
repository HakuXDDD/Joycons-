package com.joymerge.quest.gamepad

import com.joymerge.quest.core.VirtualGamepadState
import com.joymerge.quest.nativebridge.LinuxInput

/**
 * How the merged pad reaches the rest of the system.
 *
 * There is more than one implementation because there has to be: /dev/uinput is
 * the right answer, but Horizon OS is free to deny it, and the app should be
 * able to fall back without a rewrite. Backends are swappable at runtime from
 * the main screen.
 */
interface VirtualGamepadBackend {

    val id: String

    val displayName: String

    /** One honest sentence about what this backend can and cannot do. */
    val summary: String

    val requiresShizuku: Boolean

    /** Checks, without side effects where possible, whether this can work here. */
    suspend fun probe(): BackendAvailability

    suspend fun start(config: BackendConfig): BackendResult

    suspend fun stop()

    /** Pushes a state produced by the app itself (used by the Tester). */
    suspend fun submit(state: VirtualGamepadState)
}

sealed interface BackendAvailability {
    data object Available : BackendAvailability
    data class Unavailable(val reason: String) : BackendAvailability

    val isAvailable: Boolean get() = this is Available
    val reasonOrNull: String? get() = (this as? Unavailable)?.reason
}

sealed interface BackendResult {
    data class Started(val detail: String) : BackendResult
    data class Failed(val error: String) : BackendResult

    val isStarted: Boolean get() = this is Started
    val message: String get() = when (this) {
        is Started -> detail
        is Failed -> error
    }
}

data class BackendConfig(
    val deviceName: String = "JoyMerge Virtual Gamepad",
    val identity: DeviceIdentity = DeviceIdentity.NEUTRAL_VIRTUAL,
    val duplicateTriggerAxes: Boolean = true,
)

/**
 * The bus/vendor/product triple the virtual device reports.
 *
 * Default is the honest one: a virtual bus and the Linux Foundation vendor id.
 * The Xbox-compatible preset exists because Horizon OS may only apply its
 * gamepad key layout to identifiers it recognises — it is opt-in, and labelled
 * as the identifier spoofing that it is.
 */
enum class DeviceIdentity(
    val label: String,
    val busType: Int,
    val vendorId: Int,
    val productId: Int,
    val note: String,
) {
    NEUTRAL_VIRTUAL(
        label = "JoyMerge (virtual bus)",
        busType = LinuxInput.BUS_VIRTUAL,
        vendorId = 0x1D6B,
        productId = 0x4A4D,
        note = "Honest identity: BUS_VIRTUAL with the Linux Foundation vendor id. Try this first.",
    ),
    GENERIC_USB_PAD(
        label = "Generic USB gamepad",
        busType = LinuxInput.BUS_USB,
        vendorId = 0x1D6B,
        productId = 0x4A4D,
        note = "Same ids on BUS_USB. Use if Horizon OS ignores virtual-bus devices.",
    ),
    XBOX_COMPATIBLE(
        label = "Xbox Wireless Controller ids",
        busType = LinuxInput.BUS_BLUETOOTH,
        vendorId = 0x045E,
        productId = 0x02FD,
        note = "Reports Microsoft's ids so Xbox key layouts apply. This IS identifier " +
            "spoofing - only use it if the neutral identity is not recognised.",
    ),
}
