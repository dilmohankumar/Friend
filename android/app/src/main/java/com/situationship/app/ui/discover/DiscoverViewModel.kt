package com.situationship.app.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situationship.app.data.model.MatchEvent
import com.situationship.app.data.model.PublicProfile
import com.situationship.app.data.model.SwipeRequest
import com.situationship.app.data.remote.ApiService
import com.situationship.app.data.remote.errorCode
import com.situationship.app.data.remote.userMessage
import com.situationship.app.data.repo.ChatRepository
import com.situationship.app.ui.MatchPopupBus
import com.situationship.app.ui.UiMessages
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DiscoverUi(val deck: List<PublicProfile> = emptyList(), val loading: Boolean = true)
data class LikesUi(val profiles: List<PublicProfile>? = null)

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val api: ApiService,
    private val chat: ChatRepository,
    private val popups: MatchPopupBus,
    private val ui: UiMessages,
) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverUi())
    val state = _state.asStateFlow()
    private var fetching = false

    private val _likes = MutableStateFlow(LikesUi())
    val likes = _likes.asStateFlow()

    init { load() }

    /** Prefetches a small batch; the server stays the source of truth for eligibility. */
    fun load() {
        if (fetching) return
        fetching = true
        viewModelScope.launch {
            try {
                val got = api.discover(10).profiles
                _state.update { s ->
                    val have = s.deck.map { it.id }.toSet()
                    s.copy(deck = s.deck + got.filterNot { it.id in have }, loading = false)
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false) }
                ui.show(e.userMessage())
            } finally { fetching = false }
        }
    }

    fun swipe(profile: PublicProfile, like: Boolean) {
        _state.update { s -> s.copy(deck = s.deck.filterNot { it.id == profile.id }) }
        if (_state.value.deck.size < 3) load()
        viewModelScope.launch { sendSwipe(profile, like) }
    }

    private suspend fun sendSwipe(profile: PublicProfile, like: Boolean) {
        try {
            val r = api.swipe(SwipeRequest(profile.id, if (like) "like" else "pass"))
            if (r.matched && r.matchId != null) {
                popups.show(MatchEvent(r.matchId, r.user ?: profile))
                chat.refreshMatches()
            }
        } catch (e: Exception) {
            if (e.errorCode() != "profile_unavailable") ui.show(e.userMessage())
        }
    }

    // ---- Likes tab ----
    fun loadLikes() = viewModelScope.launch {
        runCatching { api.likes().profiles }
            .onSuccess { _likes.value = LikesUi(it) }
            .onFailure { _likes.value = LikesUi(emptyList()); ui.show(it.userMessage()) }
    }

    fun answerLike(profile: PublicProfile, like: Boolean) {
        _likes.update { l -> l.copy(profiles = l.profiles?.filterNot { it.id == profile.id }) }
        _state.update { s -> s.copy(deck = s.deck.filterNot { it.id == profile.id }) }
        viewModelScope.launch { sendSwipe(profile, like) }
    }
}
