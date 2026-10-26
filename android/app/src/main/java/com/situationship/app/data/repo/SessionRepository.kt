package com.situationship.app.data.repo

import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.situationship.app.BuildConfig
import com.situationship.app.data.local.MessageDao
import com.situationship.app.data.model.*
import com.situationship.app.data.remote.ApiService
import com.situationship.app.data.session.TokenStore
import com.situationship.app.data.session.Tokens
import com.situationship.app.data.socket.SocketManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/** Who is logged in and what state their account is in. The backend is always the source of truth. */
@Singleton
class SessionRepository @Inject constructor(
    private val api: ApiService,
    private val tokens: TokenStore,
    private val socket: SocketManager,
    private val messages: MessageDao,
) {
    private val _user = MutableStateFlow<SelfProfile?>(null)
    val user: StateFlow<SelfProfile?> = _user.asStateFlow()

    suspend fun hasSession(): Boolean = tokens.load() != null

    /** Re-reads account status from the server (never trust a cached "verified" flag). */
    suspend fun refreshMe(): SelfProfile = api.me().user.also { _user.value = it }

    fun setUser(u: SelfProfile) { _user.value = u }

    suspend fun requestOtp(phone: String) = api.requestOtp(OtpRequest(phone))

    suspend fun verifyOtp(phone: String, code: String): SelfProfile {
        val r = api.verifyOtp(OtpVerifyRequest(phone, code))
        tokens.save(Tokens(r.accessToken, r.refreshToken))
        return r.user?.also { _user.value = it } ?: refreshMe()
    }

    suspend fun registerPushToken() {
        if (!BuildConfig.HAS_FIREBASE) return
        try {
            val token = FirebaseMessaging.getInstance().token.await()
            api.registerDevice(DeviceRequest(token))
        } catch (e: Exception) {
            Log.w("Session", "push registration failed: ${e.message}")
        }
    }

    suspend fun logout() {
        val t = tokens.current()
        val deviceToken = if (BuildConfig.HAS_FIREBASE) runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull() else null
        if (t != null) runCatching { api.logout(LogoutRequest(t.refreshToken, deviceToken)) }
        clearLocal()
    }

    /** Wipes everything private on this device (tokens, chat cache, socket). */
    suspend fun clearLocal() {
        socket.reset()
        tokens.clear()
        messages.clear()
        _user.value = null
    }

    suspend fun deleteAccount() {
        api.deleteAccount()
        clearLocal()
    }
}
