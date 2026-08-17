package com.joymerge.quest.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/**
 * Everything JoyMerge knows about Shizuku, in one place.
 *
 * Shizuku is how a non-rooted Quest gets shell privileges: the user starts it
 * once over adb (or from another rooted-adjacent path) and it then hands out a
 * uid-2000 process on request. Without it we cannot open /dev/input or
 * /dev/uinput at all.
 *
 * This class never throws at the caller. If Shizuku is missing, the app keeps
 * running and simply reports NOT_INSTALLED — the Diagnostics screen is supposed
 * to stay usable precisely when things are broken.
 */
class ShizukuManager(private val context: Context) {

    enum class State {
        /** The Shizuku package is not on the device. */
        NOT_INSTALLED,

        /** Installed, but the manager service is not running. */
        NOT_RUNNING,

        /** Running; we have not asked for permission yet, or it is pending. */
        PERMISSION_REQUIRED,

        /** The user denied us. */
        PERMISSION_DENIED,

        /** Running and authorised. */
        READY,
    }

    data class Info(
        val state: State,
        val version: Int = -1,
        val uid: Int = -1,
        val serverPatchVersion: Int = -1,
        val detail: String = "",
    ) {
        val isReady: Boolean get() = state == State.READY
    }

    private val _info = MutableStateFlow(Info(State.NOT_INSTALLED, detail = "not checked yet"))
    val info: StateFlow<Info> = _info.asStateFlow()

    private var listenersRegistered = false

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener { refresh() }
    private val permissionResult =
        Shizuku.OnRequestPermissionResultListener { code, grantResult ->
            if (code == PERMISSION_REQUEST_CODE) {
                Log.i(TAG, "permission result: $grantResult")
                refresh()
            }
        }

    fun start() {
        if (!listenersRegistered) {
            runCatching {
                Shizuku.addBinderReceivedListenerSticky(binderReceived)
                Shizuku.addBinderDeadListener(binderDead)
                Shizuku.addRequestPermissionResultListener(permissionResult)
                listenersRegistered = true
            }.onFailure { Log.w(TAG, "could not register Shizuku listeners", it) }
        }
        refresh()
    }

    fun stop() {
        if (!listenersRegistered) return
        runCatching {
            Shizuku.removeBinderReceivedListener(binderReceived)
            Shizuku.removeBinderDeadListener(binderDead)
            Shizuku.removeRequestPermissionResultListener(permissionResult)
        }
        listenersRegistered = false
    }

    // RestrictedApi: these are Shizuku's own public entry points; the annotation
    // only scopes them to the dev.rikka group, which we are not part of.
    @Suppress("RestrictedApi")
    fun refresh(): Info {
        val next = compute()
        _info.value = next
        return next
    }

    @Suppress("RestrictedApi")
    private fun compute(): Info {
        if (!isShizukuInstalled()) {
            return Info(State.NOT_INSTALLED, detail = "no Shizuku package found (${SHIZUKU_PACKAGES.joinToString()})")
        }
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!alive) {
            return Info(State.NOT_RUNNING, detail = "Shizuku is installed but its service is not running")
        }

        val version = runCatching { Shizuku.getVersion() }.getOrDefault(-1)
        val uid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
        val patch = runCatching { Shizuku.getServerPatchVersion() }.getOrDefault(-1)

        if (runCatching { Shizuku.isPreV11() }.getOrDefault(false)) {
            return Info(
                State.PERMISSION_REQUIRED, version, uid, patch,
                "Shizuku pre-v11 is not supported; update Shizuku",
            )
        }

        return when {
            runCatching { Shizuku.checkSelfPermission() }.getOrDefault(PackageManager.PERMISSION_DENIED) ==
                PackageManager.PERMISSION_GRANTED ->
                Info(State.READY, version, uid, patch, "authorised as uid $uid")

            runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false) ->
                Info(State.PERMISSION_DENIED, version, uid, patch, "permission denied - re-grant it in Shizuku")

            else ->
                Info(State.PERMISSION_REQUIRED, version, uid, patch, "permission has not been granted yet")
        }
    }

    /** Asks the user; the answer arrives through [permissionResult]. */
    fun requestPermission(): Boolean {
        if (_info.value.state == State.NOT_INSTALLED || _info.value.state == State.NOT_RUNNING) return false
        return runCatching {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            true
        }.getOrElse {
            Log.w(TAG, "requestPermission failed", it)
            false
        }
    }

    fun isShizukuInstalled(): Boolean = installedShizukuPackage() != null

    fun installedShizukuPackage(): String? = SHIZUKU_PACKAGES.firstOrNull { packageName ->
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        }.getOrDefault(false)
    }

    /** Component to launch so the user can grant permission by hand. */
    fun managerComponent(): ComponentName? = installedShizukuPackage()?.let {
        context.packageManager.getLaunchIntentForPackage(it)?.component
    }

    companion object {
        private const val TAG = "ShizukuManager"
        const val PERMISSION_REQUEST_CODE = 4711

        /** Shizuku proper, plus Sui's package which exposes the same API. */
        val SHIZUKU_PACKAGES = listOf("moe.shizuku.privileged.api", "moe.shizuku.manager")
    }
}
