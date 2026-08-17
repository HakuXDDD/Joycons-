package com.joymerge.quest.gamepad

import com.joymerge.quest.core.VirtualGamepadState
import com.joymerge.quest.privileged.PrivilegedClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The preferred backend: a real kernel input device via /dev/uinput.
 *
 * When this one works, other apps — Xbox Cloud Gaming included — enumerate a
 * genuine `SOURCE_GAMEPAD | SOURCE_JOYSTICK` device and nothing about JoyMerge
 * is special from their point of view. It needs shell privileges, and it needs
 * SELinux to allow the shell domain to open /dev/uinput.
 */
class UInputBackend(private val client: PrivilegedClient) : VirtualGamepadBackend {

    override val id = "uinput"
    override val displayName = "uinput virtual device"
    override val summary =
        "Creates a real kernel gamepad other apps can enumerate. Needs Shizuku and an accessible /dev/uinput."
    override val requiresShizuku = true

    override suspend fun probe(): BackendAvailability = withContext(Dispatchers.IO) {
        val service = client.service
            ?: return@withContext BackendAvailability.Unavailable("privileged service is not connected")
        if (!client.nativeLoaded()) {
            return@withContext BackendAvailability.Unavailable(
                "native library not loaded in the privileged process: ${client.nativeReport()}",
            )
        }
        val report = runCatching { JSONObject(service.probeUinput()) }.getOrElse {
            return@withContext BackendAvailability.Unavailable("probeUinput failed: ${it.message}")
        }
        when {
            report.has("error") -> BackendAvailability.Unavailable(report.getString("error"))
            !report.optBoolean("exists", false) ->
                BackendAvailability.Unavailable("/dev/uinput does not exist on this device")

            report.optBoolean("openOk", false) -> BackendAvailability.Available
            else -> BackendAvailability.Unavailable(
                report.optString("openResult", "/dev/uinput could not be opened"),
            )
        }
    }

    override suspend fun start(config: BackendConfig): BackendResult = withContext(Dispatchers.IO) {
        val service = client.service
            ?: return@withContext BackendResult.Failed("privileged service is not connected")
        val raw = runCatching {
            service.startVirtualGamepad(
                config.deviceName,
                config.identity.busType,
                config.identity.vendorId,
                config.identity.productId,
                0x0100,
                config.duplicateTriggerAxes,
            )
        }.getOrElse { return@withContext BackendResult.Failed("startVirtualGamepad threw: ${it.message}") }

        val json = runCatching { JSONObject(raw) }
            .getOrElse { return@withContext BackendResult.Failed("unparseable reply: $raw") }

        if (json.optBoolean("ok", false)) {
            val sysName = json.optString("sysName", "").takeIf { it.isNotBlank() && it != "null" }
            BackendResult.Started(
                buildString {
                    append("uinput device \"${json.optString("deviceName", config.deviceName)}\" created")
                    if (sysName != null) append(" as $sysName")
                },
            )
        } else {
            BackendResult.Failed(json.optString("error", "uinput device creation failed"))
        }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        runCatching { client.service?.stopVirtualGamepad() }
        Unit
    }

    override suspend fun submit(state: VirtualGamepadState) {
        runCatching { client.service?.submitFrame(state.toFrame()) }
    }
}

/**
 * Fallback: synthesised events pushed through `InputManager.injectInputEvent`.
 *
 * Worth having because it needs no uinput access at all — but it is genuinely
 * weaker. Events land on whatever window has focus, no device appears in
 * `InputDevice.getDeviceIds()`, and apps that filter by device id may drop them.
 */
class PrivilegedInjectionBackend(private val client: PrivilegedClient) : VirtualGamepadBackend {

    override val id = "injection"
    override val displayName = "Privileged event injection"
    override val summary =
        "Injects gamepad events into the focused window. No device is created, so apps cannot enumerate it."
    override val requiresShizuku = true

    override suspend fun probe(): BackendAvailability = withContext(Dispatchers.IO) {
        client.service
            ?: return@withContext BackendAvailability.Unavailable("privileged service is not connected")
        BackendAvailability.Available
    }

    override suspend fun start(config: BackendConfig): BackendResult = withContext(Dispatchers.IO) {
        val service = client.service
            ?: return@withContext BackendResult.Failed("privileged service is not connected")
        val raw = runCatching { service.startInjectionBackend() }
            .getOrElse { return@withContext BackendResult.Failed("startInjectionBackend threw: ${it.message}") }
        val json = runCatching { JSONObject(raw) }
            .getOrElse { return@withContext BackendResult.Failed("unparseable reply: $raw") }
        if (json.optBoolean("ok", false)) {
            BackendResult.Started("Injecting into the focused window (no enumerable device)")
        } else {
            BackendResult.Failed(json.optString("error", "injection backend unavailable"))
        }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        runCatching { client.service?.stopInjectionBackend() }
        Unit
    }

    override suspend fun submit(state: VirtualGamepadState) {
        runCatching { client.service?.submitFrame(state.toFrame()) }
    }
}

/**
 * No privileges at all: the merged state is computed in-app and shown in the
 * Gamepad Tester, and goes nowhere else.
 *
 * This exists so calibration and the tester remain usable with Shizuku absent.
 * It deliberately advertises that other apps will not see anything, because
 * quietly claiming a working controller would be the single most useless thing
 * this app could do.
 */
class LoopbackBackend : VirtualGamepadBackend {

    override val id = "loopback"
    override val displayName = "In-app only (no system gamepad)"
    override val summary =
        "Merges both Joy-Cons for the Gamepad Tester only. Other apps will NOT see a controller."
    override val requiresShizuku = false

    override suspend fun probe() = BackendAvailability.Available

    override suspend fun start(config: BackendConfig): BackendResult =
        BackendResult.Started("In-app merge only - nothing is exposed to other apps")

    override suspend fun stop() = Unit

    /** Intentionally a no-op: there is nowhere for a state to go. */
    override suspend fun submit(state: VirtualGamepadState) = Unit
}
