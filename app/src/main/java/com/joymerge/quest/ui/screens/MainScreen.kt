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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joymerge.quest.shizuku.ShizukuManager
import com.joymerge.quest.ui.MainViewModel
import com.joymerge.quest.ui.Screen
import com.joymerge.quest.ui.components.InfoBanner
import com.joymerge.quest.ui.components.PrimaryAction
import com.joymerge.quest.ui.components.ProblemBanner
import com.joymerge.quest.ui.components.SecondaryAction
import com.joymerge.quest.ui.components.SectionCard
import com.joymerge.quest.ui.components.StatusLine

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val rows by viewModel.statusRows.collectAsStateWithLifecycle()
    val engine by viewModel.engine.collectAsStateWithLifecycle()
    val shizuku by viewModel.shizuku.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    val backend = viewModel.controller.backendById(settings.backendId)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "JOYMERGE QUEST",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 2.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "Two Joy-Cons in, one gamepad out.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionCard("Status") {
            Column {
                rows.forEach { StatusLine(it) }
            }
        }

        engine.error?.let { ProblemBanner(it) }

        if (profile.isEmpty) {
            InfoBanner(
                "No calibration profile yet. Nothing can be merged until JoyMerge has learned which " +
                    "code the Quest reports for each Joy-Con button - run CALIBRATE JOY-CONS first.",
            )
        }

        when (shizuku.state) {
            ShizukuManager.State.NOT_INSTALLED -> InfoBanner(
                "Shizuku is not installed. Without it the app can still calibrate and run the Gamepad " +
                    "Tester, but it cannot create a controller other apps can see. Open SET UP SHIZUKU for steps.",
            )

            ShizukuManager.State.PERMISSION_REQUIRED, ShizukuManager.State.PERMISSION_DENIED -> InfoBanner(
                "Shizuku is running but JoyMerge has no permission yet. Press GRANT SHIZUKU PERMISSION.",
            )

            else -> Unit
        }

        SectionCard(
            "Backend",
            subtitle = backend.displayName,
        ) {
            Column {
                Text(
                    backend.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Device identity: ${settings.identity.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryAction(
                text = if (engine.running) "STOP" else "START VIRTUAL GAMEPAD",
                onClick = { if (engine.running) viewModel.stopGamepad() else viewModel.startGamepad() },
                enabled = !busy,
            )

            SecondaryAction("CALIBRATE JOY-CONS", { viewModel.navigate(Screen.CALIBRATION) }, enabled = !busy)

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                SecondaryAction("GAMEPAD TESTER", { viewModel.navigate(Screen.TESTER) }, Modifier.weight(1f))
                SecondaryAction("DIAGNOSTICS", { viewModel.navigate(Screen.DIAGNOSTICS) }, Modifier.weight(1f))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                SecondaryAction("MAPPING", { viewModel.navigate(Screen.MAPPING) }, Modifier.weight(1f))
                SecondaryAction("JOY-CON SLOTS", { viewModel.navigate(Screen.DEVICES) }, Modifier.weight(1f))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                SecondaryAction("SETTINGS", { viewModel.navigate(Screen.SETTINGS) }, Modifier.weight(1f))
                SecondaryAction("SET UP SHIZUKU", { viewModel.navigate(Screen.SHIZUKU_HELP) }, Modifier.weight(1f))
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            if (!shizuku.isReady) {
                SecondaryAction(
                    "GRANT SHIZUKU PERMISSION",
                    { viewModel.requestShizukuPermission() },
                    enabled = shizuku.state != ShizukuManager.State.NOT_INSTALLED &&
                        shizuku.state != ShizukuManager.State.NOT_RUNNING,
                )
            } else {
                SecondaryAction("CONNECT PRIVILEGED SERVICE", { viewModel.connectPrivileged() }, enabled = !busy)
            }
        }

        if (engine.running) {
            SectionCard("Running") {
                Column {
                    Text(
                        engine.backendMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        engine.captureMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (engine.usesPrivilegedCapture) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (engine.exclusiveGrab) {
                                "Joy-Cons are grabbed exclusively, so the system no longer sees them as two pads."
                            } else {
                                "Exclusive grab is OFF: games will still see the two Joy-Cons alongside the merged pad."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "You can now leave JoyMerge and open Xbox Cloud Gaming.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
