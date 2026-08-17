package com.joymerge.quest.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joymerge.quest.JoyMergeApp
import com.joymerge.quest.ui.screens.CalibrationScreen
import com.joymerge.quest.ui.screens.DevicesScreen
import com.joymerge.quest.ui.screens.DiagnosticsScreen
import com.joymerge.quest.ui.screens.MainScreen
import com.joymerge.quest.ui.screens.MappingScreen
import com.joymerge.quest.ui.screens.SettingsScreen
import com.joymerge.quest.ui.screens.ShizukuHelpScreen
import com.joymerge.quest.ui.screens.TesterScreen
import com.joymerge.quest.ui.theme.JoyMergeTheme

/**
 * Single-activity host.
 *
 * Beyond drawing the UI it has one real job: Joy-Con key and motion events are
 * only delivered to the focused activity, so this is where the foreground input
 * path starts. Events belonging to an assigned Joy-Con are consumed here so a
 * stray button press cannot also activate whatever the UI has focused.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val pipeline get() = (application as JoyMergeApp).controller.foregroundPipeline

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* advisory only */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        setContent {
            JoyMergeTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    JoyMergeRoot(viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val controller = (application as JoyMergeApp).controller
        controller.shizuku.refresh()
        controller.inputDevices.refresh()
        controller.applyForegroundAssignment()
        viewModel.refreshEngineStatus()
    }

    /**
     * Foreground input path: Joy-Con presses reach the app only through here.
     *
     * Lint flags ComponentActivity.dispatchKeyEvent as a restricted API because
     * Compose overrides it for its own focus handling. Overriding it and calling
     * through to super is the supported way to see key events before the view
     * hierarchy does, which is exactly what calibration needs.
     */
    @Suppress("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (pipeline.handleKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (pipeline.handleMotion(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JoyMergeRoot(viewModel: MainViewModel) {
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(screen.title) },
                navigationIcon = {
                    if (screen != Screen.MAIN) {
                        IconButton(onClick = { viewModel.navigate(Screen.MAIN) }) {
                            Icon(backArrow, contentDescription = "Back")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (screen) {
                Screen.MAIN -> MainScreen(viewModel)
                Screen.CALIBRATION -> CalibrationScreen(viewModel)
                Screen.TESTER -> TesterScreen(viewModel)
                Screen.DIAGNOSTICS -> DiagnosticsScreen(viewModel)
                Screen.MAPPING -> MappingScreen(viewModel)
                Screen.DEVICES -> DevicesScreen(viewModel)
                Screen.SETTINGS -> SettingsScreen(viewModel)
                Screen.SHIZUKU_HELP -> ShizukuHelpScreen(viewModel)
            }
            if (busy) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth(),
                )
            }
        }
    }
}

/** A back chevron, drawn inline so the app does not pull in the icons artifact. */
private val backArrow: ImageVector by lazy {
    ImageVector.Builder(
        name = "BackArrow",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = PathData {
                moveTo(15.4f, 7.4f)
                lineTo(14f, 6f)
                lineTo(8f, 12f)
                lineTo(14f, 18f)
                lineTo(15.4f, 16.6f)
                lineTo(10.8f, 12f)
                close()
            },
            fill = SolidColor(Color.White),
            pathFillType = PathFillType.NonZero,
        )
    }.build()
}
