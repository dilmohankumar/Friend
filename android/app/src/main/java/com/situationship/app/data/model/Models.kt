package com.situationship.app.data.model

import kotlinx.serialization.Serializable

// ---------------- Users ----------------

@Serializable
data class PublicProfile(
    val id: String,
    val name: String? = null,
    val age: Int? = null,
    val gender: String? = null,
    val bio: String = "",
    val city: String = "",
    val photos: List<String> = emptyList(),
    val verified: Boolean = false,
    val online: Boolean = false,
)

@Serializable
data class SelfProfile(
    val id: String,
    val name: String? = null,
    val age: Int? = null,
    val gender: String? = null,
    val bio: String = "",
    val city: String = "",
    val photos: List<String> = emptyList(),
    val verified: Boolean = false,
    val phone: String = "",
    val status: String,
    val kycStatus: String = "none",
    val ageEligible: Boolean = false,
    val interestedIn: String? = null,
    val birthYear: Int? = null,
    val minAge: Int = 18,
    val maxAge: Int = 60,
) {
    fun toPublic() = PublicProfile(id, name, age, gender, bio, city, photos, verified, true)
}

object UserStatus {
    const val CONSENT_PENDING = "CONSENT_PENDING"
    const val IDENTITY_PENDING = "IDENTITY_PENDING"
    const val PROFILE_INCOMPLETE = "PROFILE_INCOMPLETE"
    const val ACTIVE = "ACTIVE"
}

@Serializable data class UserResponse(val user: SelfProfile)
@Serializable data class UsersResponse(val users: List<PublicProfile>)
@Serializable data class ProfilesResponse(val profiles: List<PublicProfile>)

// ---------------- Auth ----------------

@Serializable data class OtpRequest(val phone: String)
@Serializable data class OtpRequestResponse(val ok: Boolean, val phone: String, val expiresInSec: Int, val devOtp: String? = null)
@Serializable data class OtpVerifyRequest(val phone: String, val code: String)
@Serializable data class AuthResponse(val accessToken: String, val refreshToken: String, val user: SelfProfile? = null)
@Serializable data class RefreshRequest(val refreshToken: String)
@Serializable data class LogoutRequest(val refreshToken: String, val deviceToken: String? = null)
@Serializable data class OkResponse(val ok: Boolean = true)

// ---------------- KYC ----------------

@Serializable data class ConsentRequest(val accept: Boolean)
@Serializable data class KycStartResponse(val sessionId: String, val redirectUrl: String)
@Serializable data class KycStatus(
    val verificationStatus: String,
    val ageEligible: Boolean,
    val profileSetupRequired: Boolean,
    val status: String,
)

// ---------------- Profile ----------------

@Serializable
data class ProfileUpdate(
    val name: String,
    val gender: String,
    val interestedIn: String,
    val bio: String,
    val city: String,
    val minAge: Int,
    val maxAge: Int,
)

@Serializable data class DeviceRequest(val token: String, val platform: String = "android")

// ---------------- Social ----------------

@Serializable data class SwipeRequest(val targetId: String, val action: String)
@Serializable data class SwipeResponse(val matched: Boolean, val matchId: String? = null, val user: PublicProfile? = null)

@Serializable data class LastMessage(val text: String, val fromMe: Boolean, val at: Long)
@Serializable
data class MatchSummary(
    val matchId: String,
    val matchedAt: Long,
    val user: PublicProfile,
    val lastMessage: LastMessage? = null,
    val unread: Int = 0,
)
@Serializable data class MatchesResponse(val matches: List<MatchSummary>)

@Serializable
data class ChatMessage(
    val id: String,
    val matchId: String,
    val senderId: String,
    val clientMessageId: String,
    val text: String,
    val createdAt: Long,
    val deliveredAt: Long? = null,
    val readAt: Long? = null,
)
@Serializable data class MessagesResponse(val user: PublicProfile, val messages: List<ChatMessage>)

@Serializable data class BlockRequest(val userId: String)
@Serializable data class ReportRequest(val userId: String, val reason: String, val details: String = "")

// ---------------- Realtime payloads ----------------

@Serializable data class MatchEvent(val matchId: String, val user: PublicProfile)
@Serializable data class MatchIdEvent(val matchId: String)
@Serializable data class TypingEvent(val matchId: String, val isTyping: Boolean)
@Serializable data class ReadEvent(val matchId: String, val readAt: Long)
@Serializable data class StatusEvent(val id: String, val matchId: String, val deliveredAt: Long)
@Serializable data class IncomingCall(val callId: String, val matchId: String, val kind: String, val from: PublicProfile)
@Serializable data class CallIdEvent(val callId: String, val reason: String? = null)

@Serializable data class ApiError(val error: String? = null)
