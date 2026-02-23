package ie.neil.phoneman

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class TransferForegroundService : Service() {
    companion object {
        private const val CHANNEL_ID = "file_transfer"
        private const val NOTIFICATION_ID = 2001

        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_TEXT = "extra_text"
        private const val EXTRA_PERCENT = "extra_percent"
        private const val EXTRA_INDETERMINATE = "extra_indeterminate"

        private const val ACTION_START = "ie.neil.phoneman.action.START_TRANSFER"
        private const val ACTION_UPDATE = "ie.neil.phoneman.action.UPDATE_TRANSFER"
        private const val ACTION_STOP = "ie.neil.phoneman.action.STOP_TRANSFER"

        fun start(context: Context, title: String, text: String) {
            val intent = Intent(context, TransferForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_PERCENT, 0)
                putExtra(EXTRA_INDETERMINATE, true)
            }
            startCompat(context, intent)
        }

        fun update(
            context: Context,
            title: String,
            text: String,
            percent: Int,
            indeterminate: Boolean
        ) {
            val intent = Intent(context, TransferForegroundService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_PERCENT, percent.coerceIn(0, 100))
                putExtra(EXTRA_INDETERMINATE, indeterminate)
            }
            startCompat(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TransferForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            startCompat(context, intent)
        }

        private fun startCompat(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private var isStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val action = intent?.action ?: ACTION_START
            when (action) {
                ACTION_STOP -> {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                ACTION_START,
                ACTION_UPDATE -> {
                    val currentIntent = intent
                    val title = currentIntent?.getStringExtra(EXTRA_TITLE).orEmpty()
                    val text = currentIntent?.getStringExtra(EXTRA_TEXT).orEmpty()
                    val percent = currentIntent?.getIntExtra(EXTRA_PERCENT, 0) ?: 0
                    val indeterminate = currentIntent?.getBooleanExtra(EXTRA_INDETERMINATE, true) ?: true
                    val notification = buildNotification(title, text, percent, indeterminate)

                    if (!isStarted) {
                        startForeground(NOTIFICATION_ID, notification)
                        isStarted = true
                    } else {
                        val manager = getSystemService(NotificationManager::class.java)
                        manager?.notify(NOTIFICATION_ID, notification)
                    }
                }
            }
        } catch (_: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(
        title: String,
        text: String,
        percent: Int,
        indeterminate: Boolean
    ): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_file)
            .setContentTitle(title.ifBlank { getString(R.string.transfer_notification_running) })
            .setContentText(text.ifBlank { getString(R.string.transfer_notification_running) })
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent.coerceIn(0, 100), indeterminate)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.transfer_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.transfer_notification_channel_description)
        }
        manager.createNotificationChannel(channel)
    }
}
