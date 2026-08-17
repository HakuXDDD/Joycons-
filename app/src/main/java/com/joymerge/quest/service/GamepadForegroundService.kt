package com.joymerge.quest.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.joymerge.quest.JoyMergeController
import com.joymerge.quest.R
import com.joymerge.quest.ui.MainActivity

/**
 * Keeps JoyMerge alive while the user is somewhere else.
 *
 * This is the piece that makes "minimise JoyMerge, open Xbox Cloud Gaming"
 * work. The merge itself happens in the Shizuku process, but the app process
 * owns the binding to it — if Android freezes or kills us, that binding goes
 * with it, so we hold a foreground service for as long as the pad is running.
 */
class GamepadForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: getString(R.string.app_name)
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Merging Joy-Con L + R"
        startForeground(NOTIFICATION_ID, buildNotification(title, text))
        return START_STICKY
    }

    private fun buildNotification(title: String, text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, GamepadForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "joymerge_gamepad"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.joymerge.quest.action.STOP_SERVICE"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, GamepadForegroundService::class.java))
        }

        fun updateStatus(context: Context, state: JoyMergeController.EngineState) {
            if (!state.running) return
            val intent = Intent(context, GamepadForegroundService::class.java)
                .putExtra(EXTRA_TITLE, "JoyMerge virtual gamepad active")
                .putExtra(
                    EXTRA_TEXT,
                    buildString {
                        append(state.backendMessage)
                        if (state.captureMessage.isNotBlank()) {
                            append('\n')
                            append(state.captureMessage)
                        }
                    },
                )
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GamepadForegroundService::class.java))
        }
    }
}
