package com.situationship.app.push

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.situationship.app.MainActivity
import com.situationship.app.R

object Notifications {
    const val CH_MESSAGES = "messages"
    const val CH_CALLS = "calls"
    const val CH_ONGOING = "ongoing_call"
    const val INCOMING_CALL_ID = 7

    const val EXTRA_OPEN_MATCH = "open_match"
    const val EXTRA_CALL_ID = "call_id"
    const val EXTRA_ACCEPT_CALL = "accept_call"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_MESSAGES, "Messages & matches", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_CALLS, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 600, 800)
        })
        nm.createNotificationChannel(NotificationChannel(CH_ONGOING, "Ongoing call", NotificationManager.IMPORTANCE_LOW))
    }

    private fun canPost(ctx: Context) = NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    @SuppressLint("MissingPermission") // checked via areNotificationsEnabled + SecurityException guard
    fun showMessage(ctx: Context, matchId: String, title: String, body: String) {
        if (!canPost(ctx)) return
        val open = PendingIntent.getActivity(
            ctx, matchId.hashCode(),
            Intent(ctx, MainActivity::class.java).putExtra(EXTRA_OPEN_MATCH, matchId).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFFF4D6D.toInt())
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(matchId.hashCode(), n) } catch (_: SecurityException) {}
    }

    /** Full-screen incoming call (shows over the lock screen) with Accept / Decline actions. */
    @SuppressLint("MissingPermission")
    fun showIncomingCall(ctx: Context, callId: String, title: String, kind: String) {
        if (!canPost(ctx)) return
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val fullScreen = PendingIntent.getActivity(
            ctx, 10, Intent(ctx, MainActivity::class.java).putExtra(EXTRA_CALL_ID, callId).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags,
        )
        val accept = PendingIntent.getActivity(
            ctx, 11,
            Intent(ctx, MainActivity::class.java).putExtra(EXTRA_CALL_ID, callId).putExtra(EXTRA_ACCEPT_CALL, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags,
        )
        val decline = PendingIntent.getBroadcast(ctx, 12, Intent(ctx, CallActionReceiver::class.java).setAction(CallActionReceiver.DECLINE), flags)
        val n = NotificationCompat.Builder(ctx, CH_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFFF4D6D.toInt())
            .setContentTitle(title)
            .setContentText(if (kind == "video") "Incoming video call" else "Incoming voice call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(true)
            .setTimeoutAfter(35_000)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .addAction(0, "Decline", decline)
            .addAction(0, "Accept", accept)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(INCOMING_CALL_ID, n) } catch (_: SecurityException) {}
    }

    fun cancelIncomingCall(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(INCOMING_CALL_ID)
}
