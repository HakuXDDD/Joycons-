package com.joymerge.quest.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joymerge.quest.shizuku.ShizukuManager
import com.joymerge.quest.ui.MainViewModel
import com.joymerge.quest.ui.Screen
import com.joymerge.quest.ui.components.InfoBanner
import com.joymerge.quest.ui.components.MonoBlock
import com.joymerge.quest.ui.components.SecondaryAction
import com.joymerge.quest.ui.components.SectionCard

/**
 * In-app instructions for getting Shizuku running on a Quest.
 *
 * Deliberately concrete: the wireless-debugging pairing flow is the part people
 * get stuck on, and it differs from a phone because the Quest has no visible
 * developer-options tile until Developer Mode is enabled from the phone app.
 */
@Composable
fun ShizukuHelpScreen(viewModel: MainViewModel) {
    val shizuku by viewModel.shizuku.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionCard("Current state") {
            Column {
                Text(
                    shizuku.state.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    shizuku.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (shizuku.version >= 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "API version ${shizuku.version}, server uid ${shizuku.uid}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        InfoBanner(
            "JoyMerge does not need root. It needs a process running with shell privileges, and Shizuku " +
                "is what provides one. Shizuku itself has to be started once per reboot.",
        )

        SectionCard("1. Enable Developer Mode on the Quest") {
            Column {
                Step("Create an organisation at developer.oculus.com if you have not already.")
                Step("In the Meta Horizon phone app, open your headset, then Headset Settings > Developer Mode, and turn it on.")
                Step("Reboot the headset.")
            }
        }

        SectionCard("2. Install Shizuku") {
            Column {
                Step("Sideload the Shizuku APK onto the headset (SideQuest or adb install).")
                Step("Shizuku is a normal Android app; it will appear under Unknown Sources in the Quest library.")
            }
        }

        SectionCard("3. Start Shizuku over adb") {
            Column {
                Step("Connect the Quest to a computer with USB, or use wireless debugging.")
                Step("Run the start script from adb:")
                Spacer(Modifier.height(8.dp))
                MonoBlock(
                    """
                    adb devices
                    adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
                    """.trimIndent(),
                )
                Spacer(Modifier.height(8.dp))
                Step("Shizuku's own screen also lists this command; use whichever path it shows.")
                Step("This has to be repeated after every reboot of the headset - Shizuku does not survive a restart.")
            }
        }

        SectionCard("4. Grant JoyMerge permission") {
            Column {
                Step("Come back here and press GRANT SHIZUKU PERMISSION on the main screen.")
                Step("Shizuku shows a prompt; allow it.")
                Step("Then press CONNECT PRIVILEGED SERVICE and check DIAGNOSTICS - it should report uid 2000.")
            }
        }

        SectionCard("If Shizuku is not an option") {
            Text(
                "Calibration and the Gamepad Tester still work without it, using ordinary Android input " +
                    "events. What you cannot get that way is a controller other apps can see, or input that " +
                    "survives leaving this app - both need shell privileges.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (shizuku.state != ShizukuManager.State.NOT_INSTALLED) {
            SecondaryAction("GRANT SHIZUKU PERMISSION", { viewModel.requestShizukuPermission() })
        }
        SecondaryAction("BACK", { viewModel.navigate(Screen.MAIN) })
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Step(text: String) {
    Text(
        "- $text",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    )
}
