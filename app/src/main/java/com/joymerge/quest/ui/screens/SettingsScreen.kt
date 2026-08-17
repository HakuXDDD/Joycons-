package com.joymerge.quest.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joymerge.quest.gamepad.DeviceIdentity
import com.joymerge.quest.ui.MainViewModel
import com.joymerge.quest.ui.Screen
import com.joymerge.quest.ui.components.InfoBanner
import com.joymerge.quest.ui.components.ProblemBanner
import com.joymerge.quest.ui.components.SecondaryAction
import com.joymerge.quest.ui.components.SectionCard

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val availability by viewModel.backendAvailability.collectAsStateWithLifecycle()
    val engine by viewModel.engine.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (engine.running) {
            InfoBanner("The gamepad is running. Stop it before changing these; changes apply on the next start.")
        }

        SectionCard("Backend", subtitle = "how the merged pad reaches other apps") {
            Column {
                viewModel.controller.backends.forEach { backend ->
                    val state = availability[backend.id]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.selectBackend(backend.id) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        RadioButton(
                            selected = settings.backendId == backend.id,
                            onClick = { viewModel.selectBackend(backend.id) },
                        )
                        Column(Modifier.weight(1f).padding(start = 4.dp)) {
                            Text(
                                backend.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                backend.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            state?.reasonOrNull?.let {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Unavailable: $it",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            if (state?.isAvailable == true) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Probe: available",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                SecondaryAction("PROBE BACKENDS", { viewModel.probeBackends() })
            }
        }

        SectionCard("Virtual device identity") {
            Column {
                DeviceIdentity.entries.forEach { identity ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.updateSettings { it.copy(identity = identity) } }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        RadioButton(
                            selected = settings.identity == identity,
                            onClick = { viewModel.updateSettings { it.copy(identity = identity) } },
                        )
                        Column(Modifier.weight(1f).padding(start = 4.dp)) {
                            Text(
                                identity.label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                "bus=0x%02x vid=0x%04x pid=0x%04x".format(
                                    identity.busType,
                                    identity.vendorId,
                                    identity.productId,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                identity.note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (settings.identity == DeviceIdentity.XBOX_COMPATIBLE) {
                    Spacer(Modifier.height(8.dp))
                    ProblemBanner(
                        "This preset makes the virtual pad claim Microsoft's vendor and product ids. " +
                            "Use it only if the neutral identity is not recognised.",
                    )
                }
            }
        }

        SectionCard("Device name") {
            OutlinedTextField(
                value = settings.deviceName,
                onValueChange = { value -> viewModel.updateSettings { it.copy(deviceName = value) } },
                singleLine = true,
                label = { Text("uinput device name") },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionCard("Behaviour") {
            Column {
                ToggleRow(
                    title = "Take the Joy-Cons exclusively",
                    subtitle = "EVIOCGRAB stops Horizon OS seeing them as two separate pads. " +
                        "Turn off only if the grab fails or you want the raw controllers too.",
                    checked = settings.exclusiveGrab,
                    onChange = { value -> viewModel.updateSettings { it.copy(exclusiveGrab = value) } },
                )
                ToggleRow(
                    title = "Mirror triggers onto GAS/BRAKE",
                    subtitle = "Publishes LT/RT on ABS_Z + ABS_RZ and also on ABS_BRAKE + ABS_GAS, " +
                        "so titles that only read one pair still get analog triggers.",
                    checked = settings.duplicateTriggerAxes,
                    onChange = { value -> viewModel.updateSettings { it.copy(duplicateTriggerAxes = value) } },
                )
            }
        }

        SecondaryAction("BACK", { viewModel.navigate(Screen.MAIN) })
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
