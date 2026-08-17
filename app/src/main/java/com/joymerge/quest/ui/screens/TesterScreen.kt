package com.joymerge.quest.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joymerge.quest.core.VirtualButton
import com.joymerge.quest.core.VirtualGamepadState
import com.joymerge.quest.ui.MainViewModel
import com.joymerge.quest.ui.components.InfoBanner
import com.joymerge.quest.ui.components.SectionCard
import com.joymerge.quest.ui.theme.StatusIdle
import com.joymerge.quest.ui.theme.StatusOk

/**
 * Live view of the merged pad.
 *
 * This is the screen that answers "are both Joy-Cons actually being combined?"
 * before anyone wastes time launching Xbox Cloud Gaming. What it shows is the
 * merged state, not the raw controllers — if the left stick moves here, the
 * merge is working.
 */
@Composable
fun TesterScreen(viewModel: MainViewModel) {
    val state by viewModel.liveState.collectAsStateWithLifecycle()
    val engine by viewModel.engine.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        InfoBanner(
            when {
                engine.running && engine.usesPrivilegedCapture ->
                    "Source: privileged /dev/input reader (works in the background)."

                engine.running -> "Source: Android events - only while this window is focused."
                profile.androidProfile.isEmpty ->
                    "No Android-side calibration yet. Run CALIBRATE with JoyMerge focused so this screen can work."

                else -> "Source: Android events. Keep this window focused and press buttons on both Joy-Cons."
            },
        )

        SectionCard("Sticks and triggers") {
            Column {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StickIndicator("Left stick", state.leftX, state.leftY, state.isPressed(VirtualButton.L3))
                    StickIndicator("Right stick", state.rightX, state.rightY, state.isPressed(VirtualButton.R3))
                }
                Spacer(Modifier.height(16.dp))
                TriggerBar("LT", state.leftTrigger)
                Spacer(Modifier.height(8.dp))
                TriggerBar("RT", state.rightTrigger)
            }
        }

        SectionCard("D-Pad and face buttons") {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DPadIndicator(state)
                FaceButtonsIndicator(state)
            }
        }

        SectionCard("Shoulders and menu buttons") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ButtonChip("LB", state.isPressed(VirtualButton.LB), Modifier.weight(1f))
                    ButtonChip("RB", state.isPressed(VirtualButton.RB), Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ButtonChip("L3", state.isPressed(VirtualButton.L3), Modifier.weight(1f))
                    ButtonChip("R3", state.isPressed(VirtualButton.R3), Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ButtonChip("SELECT / VIEW", state.isPressed(VirtualButton.SELECT), Modifier.weight(1f))
                    ButtonChip("START / MENU", state.isPressed(VirtualButton.START), Modifier.weight(1f))
                }
                ButtonChip("GUIDE / HOME", state.isPressed(VirtualButton.MODE))
            }
        }

        SectionCard("Raw merged values") {
            Column {
                MonoValue("Left stick", "%+.3f, %+.3f".format(state.leftX, state.leftY))
                MonoValue("Right stick", "%+.3f, %+.3f".format(state.rightX, state.rightY))
                MonoValue("Triggers", "LT %.3f   RT %.3f".format(state.leftTrigger, state.rightTrigger))
                MonoValue("Button mask", "0x%04X".format(state.buttonMask))
                MonoValue(
                    "Pressed",
                    VirtualButton.entries.filter { state.isPressed(it) }.joinToString().ifBlank { "-" },
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun MonoValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun StickIndicator(label: String, x: Float, y: Float, clicked: Boolean) {
    val outline = MaterialTheme.colorScheme.outline
    val accent = if (clicked) StatusOk else MaterialTheme.colorScheme.primary
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(120.dp)) {
            val radius = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(outline, radius = radius - 2f, center = center, style = Stroke(width = 2f))
            drawCircle(outline.copy(alpha = 0.4f), radius = radius / 2f, center = center, style = Stroke(width = 1f))
            drawLine(outline.copy(alpha = 0.3f), Offset(0f, center.y), Offset(size.width, center.y))
            drawLine(outline.copy(alpha = 0.3f), Offset(center.x, 0f), Offset(center.x, size.height))
            val knob = Offset(
                center.x + x.coerceIn(-1f, 1f) * (radius - 14f),
                center.y + y.coerceIn(-1f, 1f) * (radius - 14f),
            )
            drawCircle(accent, radius = 12f, center = knob)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TriggerBar(label: String, value: Float) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(34.dp),
        )
        Canvas(
            Modifier
                .weight(1f)
                .height(18.dp)
                .clip(RoundedCornerShape(9.dp)),
        ) {
            drawRect(track, size = size)
            drawRect(fill, size = Size(size.width * value.coerceIn(0f, 1f), size.height))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            "%.2f".format(value),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(44.dp),
        )
    }
}

@Composable
private fun DPadIndicator(state: VirtualGamepadState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PadCell("^", state.isPressed(VirtualButton.DPAD_UP))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PadCell("<", state.isPressed(VirtualButton.DPAD_LEFT))
                Spacer(Modifier.size(38.dp))
                PadCell(">", state.isPressed(VirtualButton.DPAD_RIGHT))
            }
            PadCell("v", state.isPressed(VirtualButton.DPAD_DOWN))
        }
        Spacer(Modifier.height(6.dp))
        Text("D-Pad", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FaceButtonsIndicator(state: VirtualGamepadState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PadCell("Y", state.isPressed(VirtualButton.Y))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PadCell("X", state.isPressed(VirtualButton.X))
                Spacer(Modifier.size(38.dp))
                PadCell("B", state.isPressed(VirtualButton.B))
            }
            PadCell("A", state.isPressed(VirtualButton.A))
        }
        Spacer(Modifier.height(6.dp))
        Text("A / B / X / Y", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PadCell(label: String, pressed: Boolean) {
    val background = if (pressed) StatusOk else StatusIdle.copy(alpha = 0.25f)
    val textColor = if (pressed) Color(0xFF04210F) else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = textColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
private fun ButtonChip(label: String, pressed: Boolean, modifier: Modifier = Modifier) {
    val background = if (pressed) StatusOk else StatusIdle.copy(alpha = 0.22f)
    val textColor = if (pressed) Color(0xFF04210F) else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = textColor, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}
