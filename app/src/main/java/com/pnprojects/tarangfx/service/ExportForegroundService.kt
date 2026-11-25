package com.pnprojects.tarangfx.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.pnprojects.tarangfx.MainActivity
import com.pnprojects.tarangfx.R
import com.pnprojects.tarangfx.audio.ExportManager
import com.pnprojects.tarangfx.audio.JobState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Runs offline renders, conversions and AI denoising (PRD §7) with a live progress notification
 * and a Cancel action. Not exported; only the app can start it.
 */
@AndroidEntryPoint
class ExportForegroundService : LifecycleService() {

    @Inject lateinit var manager: ExportManager
    private var worker: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_CANCEL) {
            manager.cancelAll()
            return START_NOT_STICKY
        }
        ensureChannel(this)
        val notification = build("Preparing…", 0f, true)
        // Platform API on purpose: ServiceCompat masks out types it doesn't know (mediaProcessing
        // is API 35), which turns the call into an illegal "type none" FGS start on Android 15+.
        when {
            Build.VERSION.SDK_INT >= 35 ->
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= 29 ->
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> startForeground(NOTIF_ID, notification)
        }

        if (worker?.isActive != true) {
            worker = lifecycleScope.launch {
                val observer = launch {
                    manager.jobs.collectLatest { jobs ->
                        val running = jobs.firstOrNull { it.state == JobState.RUNNING } ?: return@collectLatest
                        val remaining = jobs.count { it.state == JobState.QUEUED }
                        val text = running.job.title + if (remaining > 0) "  (+$remaining queued)" else ""
                        notify(build(text, running.progress, false))
                    }
                }
                manager.processQueue()
                observer.cancel()
                ServiceCompat.stopForeground(this@ExportForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun notify(n: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }

    private fun build(text: String, progress: Float, indeterminate: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, ExportForegroundService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Processing audio")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .setProgress(100, (progress * 100).toInt(), indeterminate)
            .addAction(0, getString(R.string.cancel), cancel)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "exports"
        private const val NOTIF_ID = 42
        private const val ACTION_CANCEL = "com.pnprojects.tarangfx.action.CANCEL_EXPORT"

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel_export), NotificationManager.IMPORTANCE_LOW)
                        .apply { description = context.getString(R.string.notif_channel_export_desc) },
                )
            }
        }
    }
}
