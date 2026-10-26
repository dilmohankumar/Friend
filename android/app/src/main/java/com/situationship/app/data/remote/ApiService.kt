package com.situationship.app.data.remote

import com.situationship.app.data.model.*
import okhttp3.MultipartBody
import retrofit2.http.*

/** REST API of the Situationship backend (NestJS). */
interface ApiService {
    // Auth
    @POST("api/auth/otp/request") suspend fun requestOtp(@Body body: OtpRequest): OtpRequestResponse
    @POST("api/auth/otp/verify") suspend fun verifyOtp(@Body body: OtpVerifyRequest): AuthResponse
    @POST("api/auth/logout") suspend fun logout(@Body body: LogoutRequest): OkResponse

    // Identity verification
    @POST("api/kyc/consent") suspend fun consent(@Body body: ConsentRequest): OkResponse
    @POST("api/kyc/start") suspend fun startKyc(): KycStartResponse
    @GET("api/kyc/status") suspend fun kycStatus(): KycStatus

    // Me
    @GET("api/me") suspend fun me(): UserResponse
    @PUT("api/me/profile") suspend fun updateProfile(@Body body: ProfileUpdate): UserResponse
    @Multipart @POST("api/me/photos") suspend fun uploadPhoto(@Part photo: MultipartBody.Part): UserResponse
    @DELETE("api/me/photos/{idx}") suspend fun deletePhoto(@Path("idx") idx: Int): UserResponse
    @GET("api/me/blocked") suspend fun blocked(): UsersResponse
    @POST("api/me/devices") suspend fun registerDevice(@Body body: DeviceRequest): OkResponse
    @DELETE("api/me") suspend fun deleteAccount(): OkResponse

    // Discovery & matching
    @GET("api/discover") suspend fun discover(@Query("limit") limit: Int = 10): ProfilesResponse
    @POST("api/swipes") suspend fun swipe(@Body body: SwipeRequest): SwipeResponse
    @GET("api/likes") suspend fun likes(): ProfilesResponse
    @GET("api/matches") suspend fun matches(): MatchesResponse
    @GET("api/matches/{id}/messages") suspend fun messages(@Path("id") matchId: String, @Query("before") before: Long? = null): MessagesResponse
    @POST("api/matches/{id}/unmatch") suspend fun unmatch(@Path("id") matchId: String): OkResponse

    // Safety
    @POST("api/blocks") suspend fun block(@Body body: BlockRequest): OkResponse
    @DELETE("api/blocks/{userId}") suspend fun unblock(@Path("userId") userId: String): OkResponse
    @POST("api/reports") suspend fun report(@Body body: ReportRequest): OkResponse
}
