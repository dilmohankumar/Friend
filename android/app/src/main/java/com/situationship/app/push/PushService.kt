package com.situationship.app.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.situationship.app.data.model.DeviceRequest
import com.situationship.app.data.remote.ApiService
import com.situationship.app.data.session.TokenStore
import com.situationship.app.di.AppScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Firebase Cloud Messaging. The backend sends data-only messages:
 *   {type: "message" | "match", matchId, title, body}
 *   {type: "call", callId, matchId, kind, title}
 * When the app is in the foreground the socket already delivers these, so we skip the notification.
 */
@AndroidEntryPoint
class PushService : FirebaseMessagingService() {
    @Inject lateinit var api: ApiService
    @Inject lateinit var tokens: TokenStore
    @Inject @field:AppScope lateinit var scope: CoroutineScope

    private val inForeground get() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    override fun onNewToken(token: String) {
        scope.launch {
            if (tokens.load() != null) runCatching { api.registerDevice(DeviceRequest(token)) }
        }
    }

    override fun onMessageReceived(msg: RemoteMessage) {
        val d = msg.data
        when (d["type"]) {
            "message", "match" -> if (!inForeground) {
                Notifications.showMessage(this, d["matchId"].orEmpty(), d["title"] ?: "Situationship", d["body"].orEmpty())
            }
            "call" -> if (!inForeground) {
                Notifications.showIncomingCall(this, d["callId"].orEmpty(), d["title"] ?: "Incoming call", d["kind"] ?: "video")
            }
        }
    }
}

/** "Decline" on the incoming-call notification. The server ends the call as missed after the ring timeout. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == DECLINE) Notifications.cancelIncomingCall(context)
    }
    companion object { const val DECLINE = "com.situationship.app.DECLINE_CALL" }
}
