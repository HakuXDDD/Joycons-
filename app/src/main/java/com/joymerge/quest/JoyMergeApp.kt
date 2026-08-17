package com.joymerge.quest

import android.app.Application
import android.util.Log
import com.joymerge.quest.nativebridge.NativeBridge

/**
 * Process-wide setup.
 *
 * The native library is loaded here too, even though the app process almost
 * certainly cannot use it for anything privileged — having it loaded means
 * Diagnostics can report the unprivileged view of /dev/input and /dev/uinput
 * and show, side by side, exactly what changes once Shizuku is involved.
 */
class JoyMergeApp : Application() {

    val controller: JoyMergeController by lazy { JoyMergeController(this) }

    override fun onCreate() {
        super.onCreate()
        val loaded = NativeBridge.ensureLoaded(
            NativeBridge.candidateDirsFor(applicationInfo.nativeLibraryDir, applicationInfo.sourceDir),
            allowShellCopy = false,
        )
        Log.i(TAG, "native library loaded=$loaded (${NativeBridge.loadReport})")
        controller.start()
    }

    private companion object {
        const val TAG = "JoyMergeApp"
    }
}
