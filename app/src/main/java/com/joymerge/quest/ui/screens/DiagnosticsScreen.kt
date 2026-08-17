package com.joymerge.quest.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joymerge.quest.ui.MainViewModel
import com.joymerge.quest.ui.components.InfoBanner
import com.joymerge.quest.ui.components.MonoBlock
import com.joymerge.quest.ui.components.PrimaryAction
import com.joymerge.quest.ui.components.ProblemBanner
import com.joymerge.quest.ui.components.SecondaryAction
import com.joymerge.quest.ui.components.SectionCard
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The screen that matters most when testing on real hardware.
 *
 * Every section shows what was measured. When something failed, the failure is
 * printed with the kernel's own wording ("Permission denied", not "unavailable")
 * so a report from a Quest is actionable without guesswork.
 */
@Composable
fun DiagnosticsScreen(viewModel: MainViewModel) {
    val report by viewModel.report.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            SecondaryAction("REFRESH", { viewModel.refreshDiagnostics() }, Modifier.weight(1f), enabled = !busy)
            SecondaryAction("CONNECT", { viewModel.connectPrivileged() }, Modifier.weight(1f), enabled = !busy)
        }

        val text = report?.toPlainText()
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            SecondaryAction(
                "COPY LOG",
                {
                    text?.let {
                        copyToClipboard(context, it)
                        Toast.makeText(context, "Diagnostics copied", Toast.LENGTH_SHORT).show()
                    }
                },
                Modifier.weight(1f),
                enabled = text != null,
            )
            SecondaryAction(
                "EXPORT LOG",
                {
                    text?.let {
                        val file = exportLog(context, it)
                        if (file == null) {
                            Toast.makeText(context, "Could not write the log file", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "Saved to ${file.absolutePath}", Toast.LENGTH_LONG).show()
                            shareLog(context, file)
                        }
                    }
                },
                Modifier.weight(1f),
                enabled = text != null,
            )
        }

        if (report == null) {
            InfoBanner("Press REFRESH to collect a report.")
        }

        report?.sections?.forEach { section ->
            SectionCard(section.title) {
                Column {
                    section.problem?.let {
                        ProblemBanner(it)
                        Spacer(Modifier.height(10.dp))
                    }
                    MonoBlock(section.lines.joinToString("\n").ifBlank { "(nothing to report)" })
                }
            }
        }

        report?.let {
            SectionCard("Full report", subtitle = "exactly what COPY LOG puts on the clipboard") {
                MonoBlock(it.toPlainText())
            }
        }

        PrimaryAction("REFRESH DIAGNOSTICS", { viewModel.refreshDiagnostics() }, enabled = !busy)
        Spacer(Modifier.height(24.dp))
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("JoyMerge diagnostics", text))
}

/**
 * Writes into the app's external files dir, which is reachable over adb and MTP
 * without any storage permission — the practical way to get a log off a Quest.
 */
private fun exportLog(context: Context, text: String): File? = runCatching {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val directory = context.getExternalFilesDir(null) ?: context.filesDir
    directory.mkdirs()
    val file = File(directory, "joymerge-diagnostics-$stamp.txt")
    file.writeText(text)
    file
}.getOrNull()

private fun shareLog(context: Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "JoyMerge Quest diagnostics")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
