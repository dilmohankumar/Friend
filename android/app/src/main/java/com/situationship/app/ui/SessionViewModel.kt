package com.situationship.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situationship.app.data.model.MatchEvent
import com.situationship.app.data.model.SelfProfile
import com.situationship.app.data.model.UserStatus
import com.situationship.app.data.remote.SessionEvents
import com.situationship.app.data.remote.errorCode
import com.situationship.app.data.repo.ChatRepository
import com.situationship.app.data.repo.SessionRepository
import com.situationship.app.data.socket.RtEvent
import com.situationship.app.data.socket.SocketManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** App-wide "It's a match!" popup, fed by swipes and by realtime match events. */
@Singleton
class MatchPopupBus @Inject constructor() {
    private val _popup = MutableStateFlow<MatchEvent?>(null)
    val popup: StateFlow<MatchEvent?> = _popup.asStateFlow()
    fun show(e: MatchEvent) { if (_popup.value == null) _popup.value = e }
    fun dismiss() { _popup.value = null }
}

/** App-wide snackbar messages. */
@Singleton
class UiMessages @Inject constructor() {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages
    fun show(msg: String) { _messages.tryEmit(msg) }
}

sealed interface BootState {
    data object Loading : BootState
    data object Offline : BootState
    data object Ready : BootState
}

object Routes {
    const val LOGIN = "login"
    const val OTP = "otp/{phone}"
    fun otp(phone: String) = "otp/$phone"
    const val CONSENT = "consent"
    const val VERIFY = "verify"
    const val NOT_ELIGIBLE = "not_eligible"
    const val SETUP = "setup"
    const val DISCOVER = "discover"
    const val LIKES = "likes"
    const val CHATS = "chats"
    const val PROFILE = "profile"
    const val CHAT = "chat/{matchId}"
    fun chat(id: String) = "chat/$id"
    const val EDIT = "edit"
    const val BLOCKED = "blocked"
    const val SAFETY = "safety"

    val auth = setOf(LOGIN, OTP)
    val tabs = listOf(DISCOVER, LIKES, CHATS, PROFILE)
    val main = setOf(DISCOVER, LIKES, CHATS, PROFILE, CHAT, EDIT, BLOCKED, SAFETY)

    /** Where the server-side account status says the user must be. */
    fun required(u: SelfProfile?): String = when {
        u == null -> LOGIN
        u.status == UserStatus.CONSENT_PENDING -> CONSENT
        u.status == UserStatus.IDENTITY_PENDING && u.kycStatus == "verified" && !u.ageEligible -> NOT_ELIGIBLE
        u.status == UserStatus.IDENTITY_PENDING -> VERIFY
        u.status == UserStatus.PROFILE_INCOMPLETE -> SETUP
        u.status == UserStatus.ACTIVE -> DISCOVER
        else -> LOGIN
    }
}

@HiltViewModel
class SessionViewModel @Inject constructor(
    private val session: SessionRepository,
    private val socket: SocketManager,
    private val chat: ChatRepository,
    private val popups: MatchPopupBus,
    private val ui: UiMessages,
    private val deepLinks: DeepLinks,
    events: SessionEvents,
) : ViewModel() {

    private val _boot = MutableStateFlow<BootState>(BootState.Loading)
    val boot: StateFlow<BootState> = _boot.asStateFlow()
    val user: StateFlow<SelfProfile?> = session.user

    init {
        bootstrap()
        viewModelScope.launch { events.loggedOut.collect { session.clearLocal(); ui.show("Session expired. Please log in again.") } }
        viewModelScope.launch {
            socket.events.collect { if (it is RtEvent.NewMatch) popups.show(it.e) }
        }
        // When the account becomes ACTIVE: connect realtime + register for push
        viewModelScope.launch {
            session.user.map { it?.status == UserStatus.ACTIVE }.distinctUntilChanged().collect { active ->
                if (active) {
                    socket.connect()
                    session.registerPushToken()
                    chat.refreshMatches()
                }
            }
        }
        viewModelScope.launch {
            deepLinks.kycResult.filterNotNull().collect { result ->
                deepLinks.consumeKyc()
                runCatching { session.refreshMe() }
                ui.show(
                    when (result) {
                        "verified" -> "You're verified 🎉 Now set up your profile."
                        "underage" -> "Situationship is only for adults (18+)."
                        else -> "Verification didn't succeed. Please try again."
                    }
                )
            }
        }
    }

    fun bootstrap() {
        viewModelScope.launch {
            _boot.value = BootState.Loading
            if (!session.hasSession()) { _boot.value = BootState.Ready; return@launch }
            try {
                session.refreshMe()
                _boot.value = BootState.Ready
            } catch (e: Exception) {
                if (e.errorCode() == "network") _boot.value = BootState.Offline
                else { session.clearLocal(); _boot.value = BootState.Ready }
            }
        }
    }

    /** Re-validate status with the server (e.g. after a 403 not_eligible). */
    fun refresh() = viewModelScope.launch { runCatching { session.refreshMe() } }
}
