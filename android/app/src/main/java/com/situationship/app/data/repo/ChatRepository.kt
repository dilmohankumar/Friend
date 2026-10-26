package com.situationship.app.data.repo

import com.situationship.app.data.local.MessageDao
import com.situationship.app.data.local.MessageEntity
import com.situationship.app.data.model.ChatMessage
import com.situationship.app.data.model.MatchSummary
import com.situationship.app.data.model.LastMessage
import com.situationship.app.data.model.PublicProfile
import com.situationship.app.data.remote.ApiService
import com.situationship.app.data.remote.AppJson
import com.situationship.app.data.socket.RtEvent
import com.situationship.app.data.socket.SocketManager
import com.situationship.app.di.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chat + matches. Messages are written to Room first (optimistic, works offline), then sent over the socket
 * with a client-generated id so retries never create duplicates. Unsent messages are flushed on reconnect.
 */
@Singleton
class ChatRepository @Inject constructor(
    private val api: ApiService,
    private val dao: MessageDao,
    private val socket: SocketManager,
    private val session: SessionRepository,
    @AppScope private val scope: CoroutineScope,
) {
    private val _matches = MutableStateFlow<List<MatchSummary>>(emptyList())
    val matches: StateFlow<List<MatchSummary>> = _matches.asStateFlow()

    private val _typing = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val typing: StateFlow<Map<String, Boolean>> = _typing.asStateFlow()

    /** Match currently open on screen (no unread badge / notification for it). */
    @Volatile var openMatchId: String? = null

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors

    private val me get() = session.user.value?.id

    init {
        scope.launch { socket.connected.collect { flushOutbox(); refreshMatches() } }
        scope.launch { socket.events.collect(::onEvent) }
    }

    fun observe(matchId: String): Flow<List<MessageEntity>> = dao.observe(matchId)

    suspend fun refreshMatches() {
        runCatching { api.matches().matches }.onSuccess { list ->
            val open = openMatchId
            _matches.value = list.map { if (it.matchId == open) it.copy(unread = 0) else it }
        }
    }

    /** Loads the latest page from the server into the cache; returns the other user. */
    suspend fun loadHistory(matchId: String): PublicProfile {
        val r = api.messages(matchId)
        dao.upsertAll(r.messages.map(MessageEntity::from))
        return r.user
    }

    suspend fun send(matchId: String, text: String) {
        val uid = me ?: return
        val e = MessageEntity(
            clientMessageId = UUID.randomUUID().toString(), serverId = null, matchId = matchId, senderId = uid,
            text = text, createdAt = System.currentTimeMillis(), deliveredAt = null, readAt = null, state = "pending",
        )
        dao.upsert(e)
        bumpMatch(matchId, LastMessage(text, true, e.createdAt), incoming = false)
        deliver(e)
    }

    fun retry(e: MessageEntity) = scope.launch {
        dao.setState(e.clientMessageId, "pending")
        deliver(e.copy(state = "pending"))
    }

    private suspend fun deliver(e: MessageEntity) {
        if (!socket.isConnected) return // stays pending; flushed on reconnect
        val ack = socket.emitWithAck("message:send", JSONObject().apply {
            put("matchId", e.matchId); put("clientMessageId", e.clientMessageId); put("text", e.text)
        }) ?: return // timeout: keep pending
        val err = ack.optString("error", "")
        if (err.isNotEmpty()) {
            if (err != "rate_limited") dao.setState(e.clientMessageId, "failed")
            _errors.tryEmit(err)
            return
        }
        ack.optJSONObject("message")?.let { dao.upsert(MessageEntity.from(AppJson.decodeFromString(ChatMessage.serializer(), it.toString()))) }
    }

    private suspend fun flushOutbox() {
        for (e in dao.unsent().filter { it.state == "pending" }) deliver(e)
    }

    fun markRead(matchId: String) {
        socket.emit("message:read", JSONObject().put("matchId", matchId))
        _matches.update { list -> list.map { if (it.matchId == matchId) it.copy(unread = 0) else it } }
    }

    fun typing(matchId: String, isTyping: Boolean) {
        socket.emit("typing", JSONObject().put("matchId", matchId).put("isTyping", isTyping))
    }

    suspend fun unmatch(matchId: String) {
        api.unmatch(matchId)
        removeMatch(matchId)
    }

    suspend fun removeMatch(matchId: String) {
        _matches.update { l -> l.filterNot { it.matchId == matchId } }
        dao.deleteMatch(matchId)
    }

    private fun bumpMatch(matchId: String, last: LastMessage, incoming: Boolean) {
        val known = _matches.value.any { it.matchId == matchId }
        if (!known) { scope.launch { refreshMatches() }; return }
        _matches.update { list ->
            list.map {
                if (it.matchId != matchId) it
                else it.copy(lastMessage = last, unread = if (incoming && openMatchId != matchId) it.unread + 1 else it.unread)
            }.sortedByDescending { it.lastMessage?.at ?: it.matchedAt }
        }
    }

    private suspend fun onEvent(ev: RtEvent) {
        when (ev) {
            is RtEvent.NewMessage -> {
                dao.upsert(MessageEntity.from(ev.m))
                val mine = ev.m.senderId == me
                bumpMatch(ev.m.matchId, LastMessage(ev.m.text, mine, ev.m.createdAt), incoming = !mine)
                if (!mine) _typing.update { it - ev.m.matchId }
                if (!mine && openMatchId == ev.m.matchId) markRead(ev.m.matchId)
            }
            is RtEvent.Delivered -> dao.markDelivered(ev.e.id, ev.e.deliveredAt)
            is RtEvent.Read -> me?.let { dao.markReadByOther(ev.e.matchId, it, ev.e.readAt) }
            is RtEvent.Typing -> _typing.update { if (ev.e.isTyping) it + (ev.e.matchId to true) else it - ev.e.matchId }
            is RtEvent.NewMatch -> refreshMatches()
            is RtEvent.MatchRemoved -> removeMatch(ev.matchId)
            is RtEvent.MatchRead -> _matches.update { l -> l.map { if (it.matchId == ev.matchId) it.copy(unread = 0) else it } }
            else -> Unit
        }
    }
}
