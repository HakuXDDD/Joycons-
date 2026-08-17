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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joymerge.quest.ui.MainViewModel
import com.joymerge.quest.ui.components.InfoBanner
import com.joymerge.quest.ui.components.MonoBlock
import com.joymerge.quest.ui.components.PrimaryAction
import com.joymerge.quest.ui.components.ProblemBanner
import com.joymerge.quest.ui.components.SecondaryAction
import com.joymerge.quest.ui.components.SectionCard
import com.joymerge.quest.ui.components.StatusLine
import com.joymerge.quest.ui.StatusRow

/**
 * The calibration flow.
 *
 * Each step waits for a real press or a real stick movement. Nothing is
 * inferred from the button's name, because on Horizon OS the codes are not
 * guaranteed to match anything.
 */
@Composable
fun CalibrationScreen(viewModel: MainViewModel) {
    val state by viewModel.calibration.state.collectAsStateWithLifecycle()
    var includeSideButtons by remember { mutableStateOf(false) }

    val stepsDone = state.steps.isNotEmpty() && state.index >= state.steps.size

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!state.active && !stepsDone) {
            SectionCard("Before you start") {
                Column {
                    Text(
                        "Calibration learns two things at once: the codes Android reports while JoyMerge is " +
                            "focused, and the raw kernel codes read straight from /dev/input. The second one is " +
                            "what keeps working after you switch to Xbox Cloud Gaming, and it needs Shizuku " +
                            "connected - press CONNECT PRIVILEGED SERVICE on the main screen first if you want it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Both Joy-Cons must be paired and assigned to a slot. Check JOY-CON SLOTS if the " +
                            "main screen shows either as DISCONNECTED.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(includeSideButtons, { includeSideButtons = it })
                        Text(
                            "Also calibrate SL / SR (optional)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            PrimaryAction("START CALIBRATION", { viewModel.startCalibration(includeSideButtons) })
            return@Column
        }

        if (state.steps.isNotEmpty()) {
            LinearProgressIndicator(
                progress = { (state.index.toFloat() / state.steps.size).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (stepsDone) {
            SectionCard("All steps complete") {
                Text(
                    "Review what was captured below, then save. Anything you skipped simply stays unbound - " +
                        "you can bind it by hand later from the MAPPING screen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PrimaryAction("SAVE PROFILE", { viewModel.saveCalibration() })
        } else {
            val step = state.currentStep
            SectionCard(
                title = "Step ${state.progressLabel}",
                subtitle = step?.prompt,
            ) {
                Column {
                    Text(
                        state.prompt,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (step != null && step.phaseCount > 1) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Phase ${state.phase + 1} of ${step.phaseCount}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (step?.optional == true) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "This one is optional - skip it if your Joy-Cons do not report it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                SecondaryAction("BACK", { viewModel.calibration.previousStep() }, Modifier.weight(1f))
                SecondaryAction("SKIP", { viewModel.calibration.skipStep() }, Modifier.weight(1f))
            }
        }

        SectionCard("Listening on") {
            Column {
                StatusLine(
                    StatusRow(
                        "Android events",
                        if (state.androidActive) "ACTIVE" else "INACTIVE",
                        state.androidActive,
                        state.androidDetail,
                    ),
                )
                StatusLine(
                    StatusRow(
                        "Raw /dev/input",
                        if (state.evdevActive) "ACTIVE" else "INACTIVE",
                        state.evdevActive,
                        state.evdevDetail,
                    ),
                )
                if (!state.evdevActive) {
                    Spacer(Modifier.height(8.dp))
                    InfoBanner(
                        "Without the raw reader, the saved profile only works while JoyMerge is the focused " +
                            "app. That is enough for the Gamepad Tester, but not for playing in another app.",
                    )
                }
            }
        }

        if (state.warnings.isNotEmpty()) {
            ProblemBanner(state.warnings.joinToString("\n\n"))
        }

        SectionCard("Captured so far", subtitle = "${state.captured.size} entries") {
            if (state.captured.isEmpty()) {
                Text(
                    "Nothing yet. Press the control the step is asking for.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                MonoBlock(state.captured.joinToString("\n"))
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            SecondaryAction("CANCEL", { viewModel.calibration.cancel() }, Modifier.weight(1f), danger = true)
            if (!stepsDone) {
                SecondaryAction("SAVE NOW", { viewModel.saveCalibration() }, Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
