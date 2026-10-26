package com.situationship.app.data.remote

import com.situationship.app.BuildConfig
import com.situationship.app.data.model.ApiError
import com.situationship.app.data.model.AuthResponse
import com.situationship.app.data.model.RefreshRequest
import com.situationship.app.data.session.TokenStore
import com.situationship.app.data.session.Tokens
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
}

/** Broadcasts "session ended" (refresh token rejected) so the UI can return to login. */
@Singleton
class SessionEvents @Inject constructor() {
    private val _loggedOut = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val loggedOut: SharedFlow<Unit> = _loggedOut
    fun emitLoggedOut() { _loggedOut.tryEmit(Unit) }
}

/** Adds the access token to every request (except auth endpoints). */
class AuthInterceptor @Inject constructor(private val tokens: TokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.url.encodedPath.startsWith("/api/auth/")) return chain.proceed(req)
        val token = tokens.current()?.accessToken ?: return chain.proceed(req)
        return chain.proceed(req.newBuilder().header("Authorization", "Bearer $token").build())
    }
}

/**
 * On HTTP 401, exchanges the refresh token for a new pair (rotating refresh tokens) and retries once.
 * Synchronized so parallel requests trigger only one refresh.
 */
@Singleton
class TokenAuthenticator @Inject constructor(
    private val tokens: TokenStore,
    private val events: SessionEvents,
) : Authenticator {
    private val plain = OkHttpClient()
    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.request.url.encodedPath.startsWith("/api/auth/")) return null
        if (response.priorResponse != null) return null // already retried once
        val failedToken = response.request.header("Authorization")?.removePrefix("Bearer ")
        synchronized(lock) {
            val current = tokens.current() ?: return null
            // Another thread already refreshed: just retry with the new token
            if (current.accessToken != failedToken) {
                return response.request.newBuilder().header("Authorization", "Bearer ${current.accessToken}").build()
            }
            val fresh = refreshBlocking(current.refreshToken)
            if (fresh == null) {
                runBlocking { tokens.clear() }
                events.emitLoggedOut()
                return null
            }
            runBlocking { tokens.save(fresh) }
            return response.request.newBuilder().header("Authorization", "Bearer ${fresh.accessToken}").build()
        }
    }

    /** Also used by the socket layer when its token expires. */
    fun refreshBlocking(refreshToken: String): Tokens? = try {
        val body = AppJson.encodeToString(RefreshRequest.serializer(), RefreshRequest(refreshToken))
            .toRequestBody("application/json".toMediaType())
        plain.newCall(Request.Builder().url(BuildConfig.API_BASE_URL + "api/auth/refresh").post(body).build()).execute().use { r ->
            if (!r.isSuccessful) null
            else AppJson.decodeFromString(AuthResponse.serializer(), r.body!!.string()).let { Tokens(it.accessToken, it.refreshToken) }
        }
    } catch (e: IOException) {
        null
    }

    /** Refresh from coroutine code (e.g. socket reconnect). Returns null if the session is gone. */
    fun refreshNow(): Tokens? = synchronized(lock) {
        val current = tokens.current() ?: return null
        val fresh = refreshBlocking(current.refreshToken)
        if (fresh == null) {
            runBlocking { tokens.clear() }
            events.emitLoggedOut()
        } else runBlocking { tokens.save(fresh) }
        fresh
    }
}

/** Error code from the backend ({"error":"otp_invalid"}) or a network marker. */
fun Throwable.errorCode(): String = when (this) {
    is HttpException -> try {
        response()?.errorBody()?.string()?.let { AppJson.decodeFromString(ApiError.serializer(), it).error } ?: "http_${code()}"
    } catch (e: Exception) { "http_${code()}" }
    is IOException -> "network"
    else -> "unknown"
}

fun errorMessage(code: String): String = when (code) {
    "invalid_phone" -> "Enter a valid 10-digit Indian mobile number"
    "too_many_requests" -> "Too many attempts. Please wait a few minutes."
    "otp_invalid" -> "Incorrect code. Try again."
    "otp_expired" -> "Code expired. Request a new one."
    "too_many_attempts" -> "Too many wrong attempts. Request a new code."
    "name_required" -> "Please enter your name (min 2 characters)"
    "invalid_gender" -> "Please select your gender"
    "invalid_interest" -> "Please select who you want to meet"
    "max_photos" -> "You can add up to 6 photos"
    "invalid_image" -> "That file is not a supported image"
    "match_unavailable" -> "This conversation is no longer available"
    "rate_limited" -> "Slow down a little 🙂"
    "busy" -> "They are on another call"
    "call_unavailable" -> "This call is no longer available"
    "account_suspended" -> "This account has been suspended"
    "sms_unavailable" -> "Could not send the code right now. Please try again shortly."
    "network" -> "No internet connection"
    else -> "Something went wrong. Please try again."
}

fun Throwable.userMessage(): String = errorMessage(errorCode())
