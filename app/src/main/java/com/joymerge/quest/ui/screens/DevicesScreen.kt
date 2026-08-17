package com.joymerge.quest.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joymerge.quest.core.JoyConDetector
import com.joymerge.quest.core.JoyConSide
import com.joymerge.quest.nativebridge.LinuxInput
import com.joymerge.quest.privileged.EvdevJson
import com.joymerge.quest.ui.MainViewModel
import com.joymerge.quest.ui.Screen
import com.joymerge.quest.ui.components.InfoBanner
import com.joymerge.quest.ui.components.ProblemBanner
import com.joymerge.quest.ui.components.SecondaryAction
import com.joymerge.quest.ui.components.SectionCard

/**
 * Manual slot assignment.
 *
 * Automatic detection uses vendor/product ids first and names second, but
 * Horizon OS is free to report neither usefully — so every device is listed
 * with what we know about it, and the user gets the final say.
 */
@Composable
fun DevicesScreen(viewModel: MainViewModel) {
    val androidDevices by viewModel.androidDevices.collectAsStateWithLifecycle()
    val evdevDevices by viewModel.evdevDevices.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val evdevError by viewModel.controller.evdevError.collectAsStateWithLifecycle()
    val assignment = viewModel.controller.androidAssignment()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        InfoBanner(
            "Two lists, because there are two ways in. Android input devices drive calibration and the " +
                "Tester while JoyMerge is focused; /dev/input nodes are what the privileged reader uses when " +
                "you are playing in another app. Assign both if you can.",
        )

        SectionCard("Android input devices", subtitle = "${androidDevices.size} found") {
            Column {
                if (androidDevices.isEmpty()) {
                    Text(
                        "No input devices reported. Pair the Joy-Cons in the Quest Bluetooth settings first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidDevices.forEach { device ->
                    val verdict = JoyConDetector.classify(device.toCandidate())
                    val slot = assignment.entries.firstOrNull { it.value == device.deviceId }?.key
                    DeviceEntry(
                        title = device.name,
                        subtitle = "id ${device.deviceId} - ${device.idString} - ${device.sourceLabels.joinToString("|")}",
                        detail = "detection: ${verdict.side?.label ?: "not a Joy-Con"} (${verdict.confidence}) - ${verdict.reason}",
                        assignedTo = slot,
                        onAssign = { side -> viewModel.assignAndroidDevice(side, device.descriptor) },
                        onClear = { viewModel.assignAndroidDevice(slot ?: JoyConSide.LEFT, null) },
                    )
                }
            }
        }

        SectionCard(
            "/dev/input nodes",
            subtitle = if (evdevDevices.isEmpty()) "not read yet" else "${evdevDevices.size} nodes",
        ) {
            Column {
                evdevError?.let {
                    ProblemBanner(it)
                    Spacer(Modifier.height(10.dp))
                }
                if (evdevDevices.isEmpty()) {
                    Text(
                        "Connect the privileged service and press REFRESH to read /dev/input.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                EvdevJson.gamepadCandidates(evdevDevices).forEach { device ->
                    val verdict = JoyConDetector.classify(EvdevJson.toCandidate(device))
                    val slot = when (device.path) {
                        settings.leftEvdevPath -> JoyConSide.LEFT
                        settings.rightEvdevPath -> JoyConSide.RIGHT
                        else -> null
                    }
                    DeviceEntry(
                        title = device.name ?: device.path,
                        subtitle = "${device.path} - ${LinuxInput.busName(device.bus)} ${device.idString} - ${device.mode}",
                        detail = "detection: ${verdict.side?.label ?: "not a Joy-Con"} (${verdict.confidence}) - ${verdict.reason}",
                        assignedTo = slot,
                        onAssign = { side -> viewModel.assignEvdevPath(side, device.path) },
                        onClear = { viewModel.assignEvdevPath(slot ?: JoyConSide.LEFT, null) },
                    )
                }
                if (evdevDevices.isNotEmpty() && EvdevJson.gamepadCandidates(evdevDevices).isEmpty()) {
                    Text(
                        "None of the readable nodes advertise gamepad buttons. See DIAGNOSTICS for the " +
                            "full list including nodes that could not be opened.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            SecondaryAction("REFRESH", { viewModel.refreshEvdev() }, Modifier.weight(1f), enabled = !busy)
            SecondaryAction("CONNECT", { viewModel.connectPrivileged() }, Modifier.weight(1f), enabled = !busy)
        }
        SecondaryAction("BACK", { viewModel.navigate(Screen.MAIN) })
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DeviceEntry(
    title: String,
    subtitle: String,
    detail: String,
    assignedTo: JoyConSide?,
    onAssign: (JoyConSide) -> Unit,
    onClear: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Slot: ${assignedTo?.label ?: "none"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onAssign(JoyConSide.LEFT) }) { Text("SET L") }
            TextButton(onClick = { onAssign(JoyConSide.RIGHT) }) { Text("SET R") }
            TextButton(onClick = onClear, enabled = assignedTo != null) { Text("CLEAR") }
        }
    }
}
