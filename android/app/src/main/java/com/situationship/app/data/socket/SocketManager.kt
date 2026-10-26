package com.situationship.app.data.socket

import android.util.Log
import com.situationship.app.BuildConfig
import com.situationship.app.data.model.*
import com.situationship.app.data.remote.AppJson
import com.situationship.app.data.remote.TokenAuthenticator
import com.situationship.app.data.session.TokenStore
import com.situationship.app.di.AppScope
import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.engineio.client.transports.WebSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import org.json.JSONObject
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

sealed interface RtEvent {
    data class NewMatch(val e: MatchEvent) : RtEvent
    data class MatchRemoved(val matchId: String) : RtEvent
    data class MatchRead(val matchId: String) : RtEvent
    data class NewMessage(val m: ChatMessage) : RtEvent
    data class Delivered(val e: StatusEvent) : RtEvent
    data class Read(val e: ReadEvent) : RtEvent
    data class Typing(val e: TypingEvent) : RtEvent
    data class CallIncoming(val c: IncomingCall) : RtEvent
    data class CallAccepted(val callId: String) : RtEvent
    data class CallEnded(val callId: String, val reason: String?) : RtEvent
    data class CallOffer(val callId: String, val sdp: JSONObject) : RtEvent
    data class CallAnswer(val callId: String, val sdp: JSONObject) : RtEvent
    data class CallIce(val callId: String, val candidate: JSONObject) : RtEvent
}

enum class ConnState { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * Single authenticated Socket.IO connection to the backend.
 * Connected while the app is in the foreground or a call is active; push notifications (FCM) cover the rest.
 */
@Singleton
class SocketManager @Inject constructor(
    private val tokens: TokenStore,
    private val authenticator: TokenAuthenticator,
    @AppScope private val scope: CoroutineScope,
) {
    private val tag = "Socket"
    private var socket: Socket? = null
    private val auth = HashMap<String, String>()

    private val _events = MutableSharedFlow<RtEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<RtEvent> = _events

    private val _state = MutableStateFlow(ConnState.DISCONNECTED)
    val state: StateFlow<ConnState> = _state

    /** Fires each time the socket (re)connects — used to flush the offline outbox. */
    private val _connected = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val connected: SharedFlow<Unit> = _connected

    val isConnected get() = socket?.connected() == true

    @Synchronized
    fun connect() {
        val t = tokens.current() ?: return
        auth["token"] = t.accessToken
        socket?.let { if (!it.connected()) it.connect(); return }
        val opts = IO.Options.builder()
            .setTransports(arrayOf(WebSocket.NAME))
            .setReconnection(true)
            .setReconnectionDelay(1_000)
            .setReconnectionDelayMax(10_000)
            .setAuth(auth)
            .build()
        val s = IO.socket(URI.create(BuildConfig.API_BASE_URL.trimEnd('/')), opts)
        bind(s)
        socket = s
        _state.value = ConnState.CONNECTING
        s.connect()
    }

    @Synchronized
    fun disconnect() {
        socket?.disconnect()
        _state.value = ConnState.DISCONNECTED
    }

    @Synchronized
    fun reset() {
        socket?.off()
        socket?.disconnect()
        socket = null
        _state.value = ConnState.DISCONNECTED
    }

    private fun bind(s: Socket) {
        s.on(Socket.EVENT_CONNECT) {
            _state.value = ConnState.CONNECTED
            _connected.tryEmit(Unit)
        }
        s.on(Socket.EVENT_DISCONNECT) { _state.value = ConnState.DISCONNECTED }
        s.on(Socket.EVENT_CONNECT_ERROR) { args ->
            val msg = (args.firstOrNull() as? JSONObject)?.optString("message") ?: (args.firstOrNull() as? Exception)?.message
            Log.w(tag, "connect_error: $msg")
            _state.value = ConnState.DISCONNECTED
            when (msg) {
                // Access token expired: refresh it and retry (server-side middleware errors don't auto-reconnect)
                "auth_failed" -> scope.launch(Dispatchers.IO) {
                    val fresh = authenticator.refreshNow() ?: return@launch
                    auth["token"] = fresh.accessToken
                    s.connect()
                }
                "not_eligible" -> reset()
            }
        }
        s.onTyped("match:new", MatchEvent.serializer()) { RtEvent.NewMatch(it) }
        s.onTyped("match:removed", MatchIdEvent.serializer()) { RtEvent.MatchRemoved(it.matchId) }
        s.onTyped("match:read", MatchIdEvent.serializer()) { RtEvent.MatchRead(it.matchId) }
        s.onTyped("message:new", ChatMessage.serializer()) { RtEvent.NewMessage(it) }
        s.onTyped("message:status", StatusEvent.serializer()) { RtEvent.Delivered(it) }
        s.onTyped("message:read", ReadEvent.serializer()) { RtEvent.Read(it) }
        s.onTyped("typing", TypingEvent.serializer()) { RtEvent.Typing(it) }
        s.onTyped("call:incoming", IncomingCall.serializer()) { RtEvent.CallIncoming(it) }
        s.onTyped("call:accepted", CallIdEvent.serializer()) { RtEvent.CallAccepted(it.callId) }
        s.onTyped("call:ended", CallIdEvent.serializer()) { RtEvent.CallEnded(it.callId, it.reason) }
        s.on("call:offer") { a -> (a.firstOrNull() as? JSONObject)?.let { j -> j.optJSONObject("sdp")?.let { _events.tryEmit(RtEvent.CallOffer(j.getString("callId"), it)) } } }
        s.on("call:answer") { a -> (a.firstOrNull() as? JSONObject)?.let { j -> j.optJSONObject("sdp")?.let { _events.tryEmit(RtEvent.CallAnswer(j.getString("callId"), it)) } } }
        s.on("call:ice") { a -> (a.firstOrNull() as? JSONObject)?.let { j -> j.optJSONObject("candidate")?.let { _events.tryEmit(RtEvent.CallIce(j.getString("callId"), it)) } } }
    }

    /** Typed listener: decodes the JSON payload with kotlinx.serialization and publishes an [RtEvent]. */
    private fun <T> Socket.onTyped(event: String, ser: KSerializer<T>, map: (T) -> RtEvent) {
        on(event) { args ->
            val json = args.firstOrNull() as? JSONObject ?: return@on
            try {
                _events.tryEmit(map(AppJson.decodeFromString(ser, json.toString())))
            } catch (e: Exception) {
                Log.w(tag, "bad payload for $event: ${e.message}")
            }
        }
    }

    fun emit(event: String, payload: JSONObject) {
        socket?.emit(event, payload)
    }

    /** Emits with acknowledgement; returns the ack JSON or null on timeout / disconnected. */
    suspend fun emitWithAck(event: String, payload: JSONObject, timeoutMs: Long = 10_000): JSONObject? {
        val s = socket ?: return null
        if (!s.connected()) return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                s.emit(event, arrayOf<Any>(payload), Ack { args ->
                    if (cont.isActive) cont.resume(args.firstOrNull() as? JSONObject ?: JSONObject())
                })
            }
        }
    }
}
