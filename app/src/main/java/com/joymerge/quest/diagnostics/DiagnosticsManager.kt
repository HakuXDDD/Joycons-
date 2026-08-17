package com.joymerge.quest.diagnostics

import android.content.Context
import android.os.Build
import com.joymerge.quest.BuildConfig
import com.joymerge.quest.JoyMergeController
import com.joymerge.quest.core.JoyConDetector
import com.joymerge.quest.core.JoyConSide
import com.joymerge.quest.input.AndroidDeviceInfo
import com.joymerge.quest.input.InputTrace
import com.joymerge.quest.nativebridge.LinuxInput
import com.joymerge.quest.nativebridge.NativeBridge
import com.joymerge.quest.privileged.Evdev
import com.joymerge.quest.privileged.EvdevDeviceInfo
import com.joymerge.quest.privileged.PrivilegedClient
import com.joymerge.quest.privileged.UinputGamepad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One labelled block of the report. */
data class DiagnosticsSection(
    val title: String,
    val lines: List<String>,
    /** Set when this section represents something that is actually broken. */
    val problem: String? = null,
)

data class DiagnosticsReport(
    val generatedAt: Long,
    val sections: List<DiagnosticsSection>,
) {
    fun toPlainText(): String = buildString {
        appendLine("=== JoyMerge Quest diagnostics ===")
        appendLine("generated: ${TIMESTAMP.format(Date(generatedAt))}")
        appendLine("app: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine()
        sections.forEach { section ->
            appendLine("--- ${section.title} ---")
            section.lines.forEach { appendLine(it) }
            section.problem?.let { appendLine("PROBLEM: $it") }
            appendLine()
        }
    }

    private companion object {
        val TIMESTAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }
}

/**
 * Collects everything a person needs to work out why the merged pad is not
 * appearing on real hardware.
 *
 * The rule this file follows: report what was measured, never what was hoped
 * for. If /dev/uinput cannot be opened, the section says so with the errno the
 * kernel returned, and the virtual gamepad section says INACTIVE.
 */
class DiagnosticsManager(
    private val context: Context,
    private val controller: JoyMergeController,
) {

    suspend fun collect(): DiagnosticsReport = withContext(Dispatchers.IO) {
        val sections = mutableListOf<DiagnosticsSection>()
        sections += platformSection()
        sections += shizukuSection()
        sections += privilegedSection()
        sections += unprivilegedFilesystemSection()
        sections += privilegedFilesystemSection()
        sections += uinputSection()
        sections += androidDeviceSection()
        sections += evdevDeviceSection()
        sections += virtualGamepadSection()
        sections += recentEventsSection()
        DiagnosticsReport(System.currentTimeMillis(), sections)
    }

    private fun platformSection(): DiagnosticsSection {
        val lines = mutableListOf(
            "Android release: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            "Build fingerprint: ${Build.FINGERPRINT}",
            "Manufacturer/model: ${Build.MANUFACTURER} / ${Build.MODEL} (${Build.DEVICE})",
            "Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString()}",
            "Primary ABI: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}",
            "App native library dir: ${context.applicationInfo.nativeLibraryDir}",
            "App process uid: ${android.os.Process.myUid()}",
            "Native library in app process: ${if (NativeBridge.isLoaded) "loaded" else "NOT loaded"} " +
                "(${NativeBridge.loadReport})",
        )
        val horizon = horizonVersion()
        lines.add(1, "Horizon OS / VrOS version: ${horizon ?: "not detected (this may not be a Quest)"}")
        val problem = if (Build.SUPPORTED_ABIS.none { it == "arm64-v8a" }) {
            "This device does not support arm64-v8a; the native layer will not load."
        } else {
            null
        }
        return DiagnosticsSection("Platform", lines, problem)
    }

    /** Horizon OS exposes its version through vendor system properties. */
    private fun horizonVersion(): String? {
        val keys = listOf(
            "ro.vros.build.version",
            "ro.vros.build.version_name",
            "ro.build.version.oculus",
            "ro.oculus.build.version",
        )
        val found = keys.mapNotNull { key -> systemProperty(key)?.let { "$key=$it" } }
        return found.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    // PrivateApi: SystemProperties is the only way to read Horizon OS's version,
    // and this is read-only, best-effort, and confined to the report.
    @Suppress("PrivateApi")
    private fun systemProperty(key: String): String? = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        (get.invoke(null, key) as? String)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun shizukuSection(): DiagnosticsSection {
        val info = controller.shizuku.refresh()
        val lines = mutableListOf(
            "State: ${info.state}",
            "Detail: ${info.detail}",
            "Installed package: ${controller.shizuku.installedShizukuPackage() ?: "none"}",
            "Shizuku API version: ${if (info.version >= 0) info.version else "unknown"}",
            "Shizuku server uid: ${if (info.uid >= 0) info.uid else "unknown"}",
            "Server patch version: ${if (info.serverPatchVersion >= 0) info.serverPatchVersion else "unknown"}",
        )
        val problem = if (info.isReady) null else "Shizuku is not usable: ${info.detail}"
        return DiagnosticsSection("Shizuku", lines, problem)
    }

    private fun privilegedSection(): DiagnosticsSection {
        val connection = controller.privileged.connection.value
        val lines = mutableListOf<String>()
        var problem: String? = null
        when (connection) {
            is PrivilegedClient.Connection.Connected -> {
                lines += "Connection: CONNECTED"
                lines += "Native load report: ${controller.privileged.nativeReport()}"
                val environment = runCatching { connection.service.describeEnvironment() }.getOrNull()
                if (environment == null) {
                    lines += "describeEnvironment(): call failed"
                    problem = "the privileged service stopped responding"
                } else {
                    runCatching { JSONObject(environment) }.getOrNull()?.let { json ->
                        lines += "Privileged uid: ${json.opt("uid")} (euid ${json.opt("euid")})"
                        lines += "Process uid reported by Android: ${json.opt("processUid")}"
                        lines += "Supplementary groups: ${json.opt("groups")}"
                        lines += "SELinux context: ${json.optString("selinuxContext")}"
                        lines += "Kernel: ${json.optString("kernel")}"
                        lines += "/dev/input (privileged view): ${json.optString("inputDirectory")}"
                        if (!json.optBoolean("nativeLoaded", false)) {
                            problem = "the native library did not load in the privileged process: " +
                                json.optString("nativeReport")
                        }
                    }
                }
            }

            is PrivilegedClient.Connection.Failed -> {
                lines += "Connection: FAILED"
                lines += connection.reason
                problem = connection.reason
            }

            PrivilegedClient.Connection.Connecting -> lines += "Connection: CONNECTING"
            PrivilegedClient.Connection.Disconnected -> {
                lines += "Connection: DISCONNECTED"
                problem = "not connected to the privileged service - press START or CONNECT first"
            }
        }
        return DiagnosticsSection("Privileged service (Shizuku user service)", lines, problem)
    }

    /** What the plain app process can see. Expected to be denied, and that is the point. */
    private fun unprivilegedFilesystemSection(): DiagnosticsSection {
        val lines = mutableListOf<String>()
        if (!NativeBridge.isLoaded) {
            lines += "native library not loaded in the app process: ${NativeBridge.loadReport}"
            return DiagnosticsSection("Filesystem access (unprivileged app process)", lines)
        }
        lines += describePath(Evdev.INPUT_DIR)
        lines += describePath(UinputGamepad.UINPUT_PATH)
        lines += "Note: denial here is normal. Only the Shizuku process is expected to have access."
        return DiagnosticsSection("Filesystem access (unprivileged app process)", lines)
    }

    private fun describePath(path: String): String {
        val stat = NativeBridge.statPath(path) ?: return "$path: stat() call failed"
        if (stat[0] != 1L) {
            return "$path: MISSING or unreachable (errno ${stat[4]}: " +
                "${NativeBridge.errnoMessage(stat[4].toInt())})"
        }
        return "$path: ${LinuxInput.formatMode(stat[1])} uid=${stat[2]} gid=${stat[3]} " +
            "readable=${stat[5] == 1L} writable=${stat[6] == 1L}"
    }

    private fun privilegedFilesystemSection(): DiagnosticsSection {
        val service = controller.privileged.service
            ?: return DiagnosticsSection(
                "Filesystem access (privileged process)",
                listOf("not connected"),
                "connect the privileged service to test /dev/input access",
            )
        val environment = runCatching { JSONObject(service.describeEnvironment()) }.getOrNull()
            ?: return DiagnosticsSection(
                "Filesystem access (privileged process)",
                listOf("describeEnvironment() failed"),
                "the privileged service is not responding",
            )
        val inputDir = environment.optString("inputDirectory")
        val lines = mutableListOf("/dev/input: $inputDir")
        val problem = if (inputDir.contains("readable=false") || inputDir.contains("MISSING")) {
            "the privileged process cannot read /dev/input - reading the Joy-Cons directly is impossible"
        } else {
            null
        }
        return DiagnosticsSection("Filesystem access (privileged process)", lines, problem)
    }

    private fun uinputSection(): DiagnosticsSection {
        val service = controller.privileged.service
            ?: return DiagnosticsSection(
                "/dev/uinput",
                listOf("not tested - the privileged service is not connected"),
                "connect the privileged service to test /dev/uinput",
            )
        val json = runCatching { JSONObject(service.probeUinput()) }.getOrNull()
            ?: return DiagnosticsSection("/dev/uinput", listOf("probeUinput() failed"), "no reply from the service")

        val lines = mutableListOf(
            "Path: ${json.optString("path")}",
            "Exists: ${json.optBoolean("exists", false)}",
        )
        if (json.has("mode")) {
            lines += "Permissions: ${json.optString("mode")} uid=${json.opt("uid")} gid=${json.opt("gid")}"
            lines += "access(R_OK)=${json.optBoolean("readable")} access(W_OK)=${json.optBoolean("writable")}"
        }
        lines += "Open attempt: ${json.optString("openResult", "not attempted")}"
        json.optString("error").takeIf { it.isNotBlank() }?.let { lines += "Error: $it" }

        val problem = when {
            json.has("error") && json.optString("error").isNotBlank() -> json.optString("error")
            !json.optBoolean("exists", false) -> "/dev/uinput does not exist - the uinput backend cannot work here"
            !json.optBoolean("openOk", false) ->
                json.optString("openResult", "/dev/uinput could not be opened")

            else -> null
        }
        return DiagnosticsSection("/dev/uinput", lines, problem)
    }

    private fun androidDeviceSection(): DiagnosticsSection {
        controller.inputDevices.refresh()
        val devices = controller.inputDevices.devices.value
        val assignment = controller.androidAssignment()
        val lines = mutableListOf<String>()
        if (devices.isEmpty()) {
            lines += "no InputDevices reported by Android"
        }
        devices.forEach { device -> lines += describeAndroidDevice(device, assignment) }
        val detected = controller.inputDevices.detectJoyCons()
        lines += ""
        lines += "Auto-detected Joy-Con L: ${detected[JoyConSide.LEFT]?.name ?: "NOT FOUND"}"
        lines += "Auto-detected Joy-Con R: ${detected[JoyConSide.RIGHT]?.name ?: "NOT FOUND"}"
        val problem = when {
            detected[JoyConSide.LEFT] == null && detected[JoyConSide.RIGHT] == null ->
                "neither Joy-Con was recognised - assign them by hand in Diagnostics"

            detected[JoyConSide.LEFT] == null -> "Joy-Con L was not recognised"
            detected[JoyConSide.RIGHT] == null -> "Joy-Con R was not recognised"
            else -> null
        }
        return DiagnosticsSection("Android InputDevices", lines, problem)
    }

    private fun describeAndroidDevice(device: AndroidDeviceInfo, assignment: Map<JoyConSide, Int>): String {
        val verdict = JoyConDetector.classify(device.toCandidate())
        val slot = assignment.entries.firstOrNull { it.value == device.deviceId }?.key
        return buildString {
            appendLine("[id ${device.deviceId}] ${device.name}")
            appendLine("    VID:PID = ${device.idString}  sources=0x%08x (%s)".format(device.sources, device.sourceLabels.joinToString("|")))
            appendLine("    descriptor = ${device.descriptor}")
            appendLine("    external=${device.isExternal} virtual=${device.isVirtual} controllerNumber=${device.controllerNumber}")
            appendLine("    axes = ${device.axes.joinToString { "${it.label}[${it.min}..${it.max} flat=${it.flat}]" }}")
            appendLine("    keys = ${device.supportedKeys.joinToString { android.view.KeyEvent.keyCodeToString(it) }}")
            appendLine("    detection = ${verdict.side ?: "not a Joy-Con"} (${verdict.confidence}) - ${verdict.reason}")
            append("    assigned slot = ${slot?.label ?: "none"}")
        }
    }

    private fun evdevDeviceSection(): DiagnosticsSection {
        val devices = controller.evdevDevices.value
        val error = controller.evdevError.value
        val lines = mutableListOf<String>()
        if (error != null) lines += "error: $error"
        if (devices.isEmpty() && error == null) {
            lines += "no /dev/input nodes read yet - press REFRESH with the privileged service connected"
        }
        devices.forEach { lines += describeEvdevDevice(it) }
        return DiagnosticsSection("/dev/input nodes (privileged view)", lines, error)
    }

    private fun describeEvdevDevice(device: EvdevDeviceInfo): String = buildString {
        appendLine("${device.path}  ${device.mode} uid=${device.ownerUid} gid=${device.ownerGid}")
        if (!device.opened) {
            append("    could not open: ${device.openError ?: "unknown reason"}")
            return@buildString
        }
        appendLine("    name = ${device.name}")
        appendLine("    ${LinuxInput.busName(device.bus)} id=${device.idString} version=0x%04x".format(device.version))
        device.uniq?.let { appendLine("    uniq = $it") }
        appendLine("    key codes = ${device.keyCodes.joinToString { LinuxInput.keyName(it) }}")
        appendLine(
            "    abs axes = " + device.absAxes.joinToString {
                "${it.label}[${it.min}..${it.max} flat=${it.flat}]"
            },
        )
        val verdict = JoyConDetector.classify(
            JoyConDetector.Candidate(device.name ?: "", device.vendor, device.product, device.uniq),
        )
        append("    detection = ${verdict.side ?: "not a Joy-Con"} (${verdict.confidence}) - ${verdict.reason}")
    }

    private fun virtualGamepadSection(): DiagnosticsSection {
        val engine = controller.engine.value
        val lines = mutableListOf(
            "Engine running: ${engine.running}",
            "Backend: ${engine.backendId.ifBlank { "none" }}",
            "Backend detail: ${engine.backendMessage.ifBlank { "-" }}",
            "Capture: ${engine.captureMessage.ifBlank { "-" }}",
            "Exclusive grab active: ${engine.exclusiveGrab}",
        )
        engine.error?.let { lines += "Last error: $it" }

        val service = controller.privileged.service
        if (service != null) {
            runCatching { JSONObject(service.status()) }.getOrNull()?.let { json ->
                lines += "--- privileged status ---"
                lines += "capturing = ${json.optBoolean("capturing")}"
                lines += "slots = ${json.optJSONArray("slots")}"
                lines += "uinput active = ${json.optBoolean("uinputActive")}"
                lines += "uinput sysfs name = ${json.opt("uinputSysName")}"
                json.optString("uinputError").takeIf { it.isNotBlank() }?.let { lines += "uinput error = $it" }
                lines += "injection enabled = ${json.optBoolean("injectionEnabled")}"
                json.optString("injectionError").takeIf { it.isNotBlank() }?.let {
                    lines += "injection error = $it"
                }
                lines += "profile = ${json.optString("profile")}"
            }
        }

        val problem = when {
            engine.error != null -> engine.error
            !engine.running -> "virtual gamepad is INACTIVE"
            else -> null
        }
        return DiagnosticsSection("Virtual gamepad", lines, problem)
    }

    private fun recentEventsSection(): DiagnosticsSection {
        val traces = controller.foregroundPipeline.traces.value
        val lines = mutableListOf<String>()
        JoyConSide.entries.forEach { side ->
            val forSide = traces.filter { it.side == side }.take(12)
            lines += "${side.label}: ${if (forSide.isEmpty()) "no events received" else ""}"
            forSide.forEach { lines += "    ${formatTrace(it)}" }
        }
        lines += ""
        lines += "These are Android-level events and only arrive while JoyMerge is in the foreground."
        return DiagnosticsSection("Last events received (foreground)", lines)
    }

    private fun formatTrace(trace: InputTrace): String =
        "${trace.label} (${if (trace.isAxis) "axis" else "key"} ${trace.code}) = ${trace.value}"
}
