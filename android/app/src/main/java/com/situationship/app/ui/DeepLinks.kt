package com.situationship.app.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** One-shot navigation requests coming from outside Compose (deep links, notification taps). */
@Singleton
class DeepLinks @Inject constructor() {
    private val _kycResult = MutableStateFlow<String?>(null)
    val kycResult: StateFlow<String?> = _kycResult.asStateFlow()

    private val _openChat = MutableStateFlow<String?>(null)
    val openChat: StateFlow<String?> = _openChat.asStateFlow()

    fun kycReturned(result: String?) { _kycResult.value = result ?: "unknown" }
    fun consumeKyc() { _kycResult.value = null }

    fun openChat(matchId: String) { _openChat.value = matchId }
    fun consumeOpenChat() { _openChat.value = null }
}
