package com.joymerge.quest.privileged

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.joymerge.quest.BuildConfig
import com.joymerge.quest.core.VirtualGamepadState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import rikka.shizuku.Shizuku

/**
 * App-side handle on [JoyMergeUserService].
 *
 * Binding goes through Shizuku, which spawns the service in its own process
 * with shell privileges. Every failure mode is represented explicitly so the
 * UI can say what went wrong rather than showing a spinner forever.
 */
class PrivilegedClient(private val context: Context) {

    sealed interface Connection {
        data object Disconnected : Connection
        data object Connecting : Connection
        data class Connected(val service: IJoyMergeService, val initReport: String) : Connection
        data class Failed(val reason: String) : Connection
    }

    /** One raw evdev event as forwarded by the privileged reader. */
    data class RawEvent(val slot: Int, val type: Int, val code: Int, val value: Int, val at: Long)

    private val _connection = MutableStateFlow<Connection>(Connection.Disconnected)
    val connection: StateFlow<Connection> = _connection.asStateFlow()

    private val _state = MutableStateFlow(VirtualGamepadState.NEUTRAL)
    val state: StateFlow<VirtualGamepadState> = _state.asStateFlow()

    private val _rawEvents = MutableSharedFlow<RawEvent>(replay = 0, extraBufferCapacity = 256)
    val rawEvents: SharedFlow<RawEvent> = _rawEvents.asSharedFlow()

    private val _logs = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 128)
    val logs: SharedFlow<String> = _logs.asSharedFlow()

    private var pending: CompletableDeferred<Connection>? = null

    val service: IJoyMergeService?
        get() = (_connection.value as? Connection.Connected)?.service

    private val listener = object : IJoyMergeListener.Stub() {
        override fun onState(frame: IntArray?) {
            if (frame == null || frame.size < VirtualGamepadState.FRAME_SIZE) return
            _state.value = VirtualGamepadState.fromFrame(frame)
        }

        override fun onRawEvent(slot: Int, type: Int, code: Int, value: Int) {
            _rawEvents.tryEmit(RawEvent(slot, type, code, value, System.currentTimeMillis()))
        }

        override fun onLog(line: String?) {
            line?.let { _logs.tryEmit(it) }
        }

        override fun onCaptureError(slot: Int, message: String?) {
            _logs.tryEmit("capture slot $slot error: ${message ?: "unknown"}")
        }
    }

    private val serviceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(context.packageName, JoyMergeUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("privileged")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) {
                finish(Connection.Failed("Shizuku returned a dead binder"))
                return
            }
            val remote = IJoyMergeService.Stub.asInterface(binder)
            val report = runCatching {
                remote.initialize(context.applicationInfo.nativeLibraryDir)
            }.getOrElse { "{\"nativeLoaded\":false,\"nativeReport\":\"initialize() threw ${it.message}\"}" }

            runCatching { remote.setListener(listener) }
                .onFailure { Log.w(TAG, "setListener failed", it) }

            finish(Connection.Connected(remote, report))
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "privileged service disconnected")
            _connection.value = Connection.Disconnected
            _state.value = VirtualGamepadState.NEUTRAL
        }
    }

    private fun finish(result: Connection) {
        _connection.value = result
        pending?.complete(result)
        pending = null
    }

    /**
     * Binds the privileged service, waiting up to [timeoutMs] for Shizuku to
     * spawn it. Safe to call repeatedly.
     */
    suspend fun connect(timeoutMs: Long = 15_000): Connection {
        (_connection.value as? Connection.Connected)?.let { return it }

        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            return Connection.Failed("Shizuku service is not running").also { _connection.value = it }
        }

        val deferred = CompletableDeferred<Connection>()
        pending = deferred
        _connection.value = Connection.Connecting

        val bindError = runCatching { Shizuku.bindUserService(serviceArgs, serviceConnection) }.exceptionOrNull()
        if (bindError != null) {
            val failure = Connection.Failed("bindUserService failed: ${bindError.message ?: bindError.toString()}")
            finish(failure)
            return failure
        }

        return withTimeoutOrNull(timeoutMs) { deferred.await() }
            ?: Connection.Failed("timed out waiting for the privileged service to start").also {
                _connection.value = it
                pending = null
            }
    }

    fun disconnect() {
        runCatching { service?.setListener(null) }
        runCatching { Shizuku.unbindUserService(serviceArgs, serviceConnection, true) }
        _connection.value = Connection.Disconnected
        _state.value = VirtualGamepadState.NEUTRAL
    }

    /** True only if the privileged side reports the native library actually loaded. */
    fun nativeLoaded(): Boolean {
        val connected = _connection.value as? Connection.Connected ?: return false
        return runCatching { JSONObject(connected.initReport).optBoolean("nativeLoaded", false) }
            .getOrDefault(false)
    }

    fun nativeReport(): String {
        val connected = _connection.value as? Connection.Connected ?: return "not connected"
        return runCatching { JSONObject(connected.initReport).optString("nativeReport", "unknown") }
            .getOrDefault(connected.initReport)
    }

    private companion object {
        const val TAG = "PrivilegedClient"
    }
}
