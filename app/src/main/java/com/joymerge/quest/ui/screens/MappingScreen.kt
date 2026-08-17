package com.joymerge.quest.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joymerge.quest.core.AnalogBinding
import com.joymerge.quest.core.CalibrationSteps
import com.joymerge.quest.core.DigitalBinding
import com.joymerge.quest.core.JoyConSide
import com.joymerge.quest.core.MappingProfile
import com.joymerge.quest.core.SignalDomain
import com.joymerge.quest.core.VirtualAxis
import com.joymerge.quest.core.VirtualButton
import com.joymerge.quest.nativebridge.LinuxInput
import com.joymerge.quest.ui.MainViewModel
import com.joymerge.quest.ui.Screen
import com.joymerge.quest.ui.components.InfoBanner
import com.joymerge.quest.ui.components.SecondaryAction
import com.joymerge.quest.ui.components.SectionCard

/**
 * Manual override for whatever calibration produced.
 *
 * Each control can be re-learned individually — which runs a one-step
 * calibration and leaves everything else alone — or cleared outright.
 */
@Composable
fun MappingScreen(viewModel: MainViewModel) {
    val bundle by viewModel.profile.collectAsStateWithLifecycle()
    var domain by remember { mutableStateOf(SignalDomain.EVDEV) }
    val profile = bundle.profileFor(domain)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SignalDomain.entries.forEach { entry ->
                FilterChip(
                    selected = domain == entry,
                    onClick = { domain = entry },
                    label = { Text(if (entry == SignalDomain.EVDEV) "Raw evdev" else "Android") },
                )
            }
        }

        InfoBanner(
            when (domain) {
                SignalDomain.EVDEV ->
                    "The kernel-level mapping. This is the one used when the merged pad runs in the " +
                        "background, and the only one that matters for Xbox Cloud Gaming."

                SignalDomain.ANDROID ->
                    "The Android-event mapping, used by the Gamepad Tester and by the in-app-only backend. " +
                        "It stops receiving events as soon as JoyMerge loses focus."
            },
        )

        if (profile.isEmpty) {
            InfoBanner("Nothing is bound in this vocabulary yet. Run CALIBRATE JOY-CONS first.")
        }

        SectionCard("Analog outputs", subtitle = "${profile.axes.size + profile.digitalAxes.size} bound") {
            Column {
                VirtualAxis.entries.forEach { axis ->
                    AxisRow(viewModel, profile, axis, domain)
                }
            }
        }

        SectionCard("Buttons", subtitle = "${profile.buttons.count { it.value.isNotEmpty() }} bound") {
            Column {
                VirtualButton.entries.forEach { button ->
                    ButtonRow(viewModel, profile, button, domain)
                }
            }
        }

        SecondaryAction("CLEAR THE WHOLE PROFILE", { viewModel.clearCalibration() }, danger = true)
        SecondaryAction("BACK", { viewModel.navigate(Screen.MAIN) })
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AxisRow(
    viewModel: MainViewModel,
    profile: MappingProfile,
    axis: VirtualAxis,
    domain: SignalDomain,
) {
    val analog = profile.axes[axis]
    val digital = profile.digitalAxes[axis]
    val description = when {
        analog != null -> describeAnalog(analog, domain)
        digital != null && !digital.isEmpty ->
            "digital: " + (digital.positive + digital.negative).joinToString { describeDigital(it, domain) }

        else -> "not bound"
    }
    BindingRow(
        label = axis.label,
        description = description,
        onLearn = {
            viewModel.calibration.startSingle(CalibrationSteps.forAxis(axis, defaultSideFor(axis)))
            viewModel.navigate(Screen.CALIBRATION)
        },
        onClear = {
            viewModel.controller.store.saveMapping(
                profile.withAxis(axis, null).withDigitalAxis(axis, null),
            )
        },
        clearEnabled = analog != null || digital?.isEmpty == false,
    )
}

@Composable
private fun ButtonRow(
    viewModel: MainViewModel,
    profile: MappingProfile,
    button: VirtualButton,
    domain: SignalDomain,
) {
    val bindings = profile.buttons[button].orEmpty()
    BindingRow(
        label = button.label,
        description = if (bindings.isEmpty()) {
            "not bound"
        } else {
            bindings.joinToString { describeDigital(it, domain) }
        },
        onLearn = {
            viewModel.calibration.startSingle(CalibrationSteps.forButton(button, defaultSideFor(button)))
            viewModel.navigate(Screen.CALIBRATION)
        },
        onClear = { viewModel.controller.store.saveMapping(profile.withButton(button, emptyList())) },
        clearEnabled = bindings.isNotEmpty(),
    )
}

@Composable
private fun BindingRow(
    label: String,
    description: String,
    onLearn: () -> Unit,
    onClear: () -> Unit,
    clearEnabled: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onLearn) { Text("LEARN") }
        TextButton(onClick = onClear, enabled = clearEnabled) { Text("CLEAR") }
    }
}

private fun describeAnalog(binding: AnalogBinding, domain: SignalDomain): String {
    val code = if (domain == SignalDomain.EVDEV) {
        LinuxInput.absName(binding.code)
    } else {
        android.view.MotionEvent.axisToString(binding.code)
    }
    return "${binding.side.label} $code [${binding.rawMin}..${binding.rawMax}]" +
        (if (binding.inverted) " inverted" else "")
}

private fun describeDigital(binding: DigitalBinding, domain: SignalDomain): String {
    val code = when {
        domain == SignalDomain.EVDEV && binding.type == com.joymerge.quest.core.SignalType.KEY ->
            LinuxInput.keyName(binding.code)

        domain == SignalDomain.EVDEV -> LinuxInput.absName(binding.code)
        binding.type == com.joymerge.quest.core.SignalType.KEY ->
            android.view.KeyEvent.keyCodeToString(binding.code)

        else -> android.view.MotionEvent.axisToString(binding.code)
    }
    val suffix = when (binding.type) {
        com.joymerge.quest.core.SignalType.AXIS_POSITIVE -> " +"
        com.joymerge.quest.core.SignalType.AXIS_NEGATIVE -> " -"
        com.joymerge.quest.core.SignalType.KEY -> ""
    }
    return "${binding.side.label} $code$suffix"
}

/** Which Joy-Con we expect a control to come from; only affects the prompt text. */
private fun defaultSideFor(axis: VirtualAxis): JoyConSide = when (axis) {
    VirtualAxis.LEFT_X, VirtualAxis.LEFT_Y, VirtualAxis.LEFT_TRIGGER -> JoyConSide.LEFT
    else -> JoyConSide.RIGHT
}

private fun defaultSideFor(button: VirtualButton): JoyConSide = when (button) {
    VirtualButton.DPAD_UP, VirtualButton.DPAD_DOWN, VirtualButton.DPAD_LEFT, VirtualButton.DPAD_RIGHT,
    VirtualButton.LB, VirtualButton.L3, VirtualButton.SELECT,
    -> JoyConSide.LEFT

    else -> JoyConSide.RIGHT
}
