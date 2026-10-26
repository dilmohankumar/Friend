package com.situationship.app.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situationship.app.call.CallManager
import com.situationship.app.data.local.MessageEntity
import com.situationship.app.data.model.BlockRequest
import com.situationship.app.data.model.PublicProfile
import com.situationship.app.data.model.ReportRequest
import com.situationship.app.data.remote.ApiService
import com.situationship.app.data.remote.userMessage
import com.situationship.app.data.repo.ChatRepository
import com.situationship.app.data.repo.SessionRepository
import com.situationship.app.data.socket.RtEvent
import com.situationship.app.data.socket.SocketManager
import com.situationship.app.ui.UiMessages
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

data class ConversationUi(
    val other: PublicProfile? = null,
    val loading: Boolean = true,
    val online: Boolean = false,
    val gone: Boolean = false,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val chat: ChatRepository,
    private val api: ApiService,
    private val socket: SocketManager,
    private val calls: CallManager,
    private val ui: UiMessages,
    session: SessionRepository,
) : ViewModel() {
    val matchId: String = saved.get<String>("matchId").orEmpty()
    val myId: String? = session.user.value?.id

    private val _state = MutableStateFlow(ConversationUi())
    val state = _state.asStateFlow()

    val messages: StateFlow<List<MessageEntity>> =
        chat.observe(matchId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val typing: StateFlow<Boolean> = chat.typing.map { it[matchId] == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var typingJob: Job? = null
    private var typingSent = false

    init {
        chat.openMatchId = matchId
        viewModelScope.launch {
            try {
                val other = chat.loadHistory(matchId)
                _state.update { it.copy(other = other, loading = false, online = other.online) }
                chat.markRead(matchId)
                checkPresence()
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, gone = true) }
                ui.show(e.userMessage())
            }
        }
        viewModelScope.launch {
            socket.events.collect { ev ->
                when (ev) {
                    is RtEvent.MatchRemoved -> if (ev.matchId == matchId) {
                        _state.update { it.copy(gone = true) }; ui.show("This match is no longer available")
                    }
                    is RtEvent.Typing -> if (ev.e.matchId == matchId) _state.update { it.copy(online = true) }
                    else -> Unit
                }
            }
        }
        // re-sync after reconnects
        viewModelScope.launch { socket.connected.collect { runCatching { chat.loadHistory(matchId) }; chat.markRead(matchId); checkPresence() } }
    }

    private suspend fun checkPresence() {
        val r = socket.emitWithAck("presence:get", JSONObject().put("matchId", matchId), 5_000)
        _state.update { it.copy(online = r?.optBoolean("online") == true) }
    }

    fun onScreenVisible() { chat.openMatchId = matchId; chat.markRead(matchId) }
    fun onScreenHidden() { if (chat.openMatchId == matchId) chat.openMatchId = null }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        stopTyping()
        viewModelScope.launch { chat.send(matchId, t) }
    }

    fun retry(m: MessageEntity) { chat.retry(m) }

    fun onTyping() {
        if (!typingSent) { chat.typing(matchId, true); typingSent = true }
        typingJob?.cancel()
        typingJob = viewModelScope.launch { delay(1_500); stopTyping() }
    }

    private fun stopTyping() {
        typingJob?.cancel()
        if (typingSent) { chat.typing(matchId, false); typingSent = false }
    }

    fun call(kind: String) {
        val other = _state.value.other ?: return
        calls.startCall(matchId, other, kind)
    }

    fun unmatch(onDone: () -> Unit) = viewModelScope.launch {
        try { chat.unmatch(matchId); ui.show("Unmatched"); onDone() } catch (e: Exception) { ui.show(e.userMessage()) }
    }

    fun block(onDone: () -> Unit) = viewModelScope.launch {
        val id = _state.value.other?.id ?: return@launch
        try { api.block(BlockRequest(id)); chat.removeMatch(matchId); ui.show("Blocked"); onDone() } catch (e: Exception) { ui.show(e.userMessage()) }
    }

    fun report(reason: String, details: String, alsoBlock: Boolean, onDone: () -> Unit) = viewModelScope.launch {
        val id = _state.value.other?.id ?: return@launch
        try {
            api.report(ReportRequest(id, reason, details))
            if (alsoBlock) { api.block(BlockRequest(id)); chat.removeMatch(matchId); onDone() }
            ui.show(if (alsoBlock) "Reported and blocked. Thank you." else "Report sent. Thank you for keeping Situationship safe.")
        } catch (e: Exception) { ui.show(e.userMessage()) }
    }

    override fun onCleared() {
        stopTyping()
        onScreenHidden()
    }
}
