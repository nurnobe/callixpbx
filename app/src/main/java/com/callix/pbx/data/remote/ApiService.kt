package com.callix.pbx.data.remote

import com.callix.pbx.data.model.*
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    // ==================== Authentication ====================

    @POST("api/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    @POST("api/auth/logout")
    suspend fun logout(
        @Header("Authorization") token: String
    ): Response<ApiResponse<Any>>

    @GET("api/auth/profile")
    suspend fun getProfile(
        @Header("Authorization") token: String
    ): Response<ApiResponse<UserData>>

    @PUT("api/auth/profile")
    suspend fun updateProfile(
        @Header("Authorization") token: String,
        @Body profileData: Map<String, String>
    ): Response<ApiResponse<UserData>>

    @PUT("api/auth/fcm-token")
    suspend fun updateFcmToken(
        @Header("Authorization") token: String,
        @Body body: Map<String, String>
    ): Response<ApiResponse<Any>>

    // ==================== Call History ====================

    /**
     * Returns [CallRecord] (see ScreenModels.kt), not the older [CallHistory]:
     * the rows now carry recordingUrl + hasRecording, which the log needs in
     * order to offer playback of the caller's OWN recording.
     */
    @GET("api/calls/history")
    suspend fun getCallHistory(
        @Header("Authorization") token: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 50,
        @Query("direction") direction: String? = null
    ): Response<CallRecordResponse>

    @DELETE("api/calls/history/{id}")
    suspend fun deleteCallRecord(
        @Header("Authorization") token: String,
        @Path("id") id: String
    ): Response<ApiResponse<Any>>

    @DELETE("api/calls/history")
    suspend fun clearCallHistory(
        @Header("Authorization") token: String
    ): Response<ApiResponse<Any>>

    // ==================== Contacts ====================

    /**
     * The PBX directory. Returns [ServerContact] (see ScreenModels.kt); the screen
     * merges these rows with the phone's own address book into a single list.
     */
    @GET("api/contacts")
    suspend fun getContacts(
        @Header("Authorization") token: String,
        @Query("search") search: String? = null
    ): Response<ServerContactResponse>

    @GET("api/contacts/{id}")
    suspend fun getContact(
        @Header("Authorization") token: String,
        @Path("id") id: String
    ): Response<ApiResponse<Contact>>

    // ==================== Location (Legacy) ====================

    @POST("api/location/update")
    suspend fun updateLocation(
        @Header("Authorization") token: String,
        @Body location: LocationUpdate
    ): Response<LocationResponse>

    @GET("api/location/users")
    suspend fun getTeamLocations(
        @Header("Authorization") token: String
    ): Response<ApiResponse<List<Map<String, Any>>>>

    // ==================== PBX Extension Locations ====================

    /** POST /api/pbx/extension-locations — Post GPS location fix */
    @POST("api/pbx/extension-locations")
    suspend fun postExtensionLocation(
        @Header("Authorization") token: String,
        @Body body: ExtensionLocationRequest
    ): Response<ExtensionLocationResponse>

    /** GET /api/pbx/extensions — List of extensions */
    @GET("api/pbx/extensions")
    suspend fun getPbxExtensions(
        @Header("Authorization") token: String
    ): Response<ApiResponse<List<PbxExtension>>>
}
