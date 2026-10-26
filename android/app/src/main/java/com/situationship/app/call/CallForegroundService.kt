package com.situationship.app.call

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.situationship.app.MainActivity
import com.situationship.app.R
import com.situationship.app.push.Notifications

/**
 * Keeps an ongoing call (camera + microphone) alive while the app is in the background,
 * as required by Android's foreground-service rules.
 */
class CallForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val name = intent?.getStringExtra(EXTRA_NAME).orEmpty()
        val video = intent?.getBooleanExtra(EXTRA_VIDEO, false) ?: false
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, Notifications.CH_ONGOING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (name.isNotEmpty()) "Call with $name" else "Situationship call")
            .setContentText(if (video) "Video call in progress" else "Voice call in progress")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(open)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or (if (video) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
        } else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
        } catch (e: Exception) {
            // e.g. permission revoked mid-call: keep the call running without the service
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        const val EXTRA_NAME = "name"
        const val EXTRA_VIDEO = "video"
        const val NOTIFICATION_ID = 42
    }
}
