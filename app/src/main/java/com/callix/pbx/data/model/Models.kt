package com.callix.pbx.data.model

import com.google.gson.annotations.SerializedName

// ==================== Auth Models ====================

/** Login uses only the assigned PBX extension/username and password. */
data class LoginRequest(
    @SerializedName("username") val username: String? = null,
    @SerializedName("password") val password: String
)

data class LoginResponse(
    @SerializedName("token")   val token: String?   = null,
    @SerializedName("success") val success: Boolean? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("error")   val error: String?   = null,
    @SerializedName("data")    val data: UserData?  = null,
    @SerializedName("user")    val user: UserInfo?  = null
)

data class UserInfo(
    @SerializedName("id")              val id: Any? = null,
    @SerializedName("email")           val email: String? = null,
    @SerializedName("name")            val name: String? = null,
    @SerializedName("username")        val username: String? = null,
    @SerializedName("extension")       val extension: String? = null,
    @SerializedName("organization_id") val organizationId: Any? = null
)

data class UserData(
    @SerializedName("id")           val id: Any? = null,
    @SerializedName("username")     val username: String? = null,
    @SerializedName("email")        val email: String? = null,
    @SerializedName("display_name") val displayName: String? = null,
    @SerializedName("name")         val name: String? = null,
    @SerializedName("extension")    val extension: String? = null,
    @SerializedName("sip_username") val sipUsername: String? = null,
    @SerializedName("sip_server")   val sipServer: String? = null,
    @SerializedName("sip_port")     val sipPort: Int = 5060,
    @SerializedName("sip_password") val sipPassword: String? = null,
    @SerializedName("transport")    val transport: String = "UDP",
    @SerializedName("avatar_url")   val avatarUrl: String? = null,
    @SerializedName("token")        val token: String? = null
)

// ==================== Call Models ====================

data class CallHistory(
    @SerializedName("id") val id: Any? = null,
    // /api/calls/history emits camelCase (callerId, callerName, calleeId,
    // calleeName, startTime). This model declared snake_case, so Gson matched
    // nothing and every row rendered with a blank number, a blank name and a
    // blank time -- "call history te number show kore na". Both spellings are
    // accepted so a future server rename cannot blank the row again, and each
    // field now has a default so a missing key can never inject a null.
    @SerializedName(value = "callerId", alternate = ["caller_id"]) val callerId: String = "",
    @SerializedName(value = "callerName", alternate = ["caller_name"]) val callerName: String? = null,
    @SerializedName(value = "calleeId", alternate = ["callee_id"]) val calleeId: String = "",
    @SerializedName(value = "calleeName", alternate = ["callee_name"]) val calleeName: String? = null,
    @SerializedName(value = "direction", alternate = ["call_direction"]) val direction: String = "", // "inbound" / "outbound"
    @SerializedName(value = "status", alternate = ["call_status"]) val status: String = "", // "answered", "missed", "busy"
    @SerializedName(value = "duration", alternate = ["billsec"]) val duration: Int = 0, // seconds
    @SerializedName(value = "startTime", alternate = ["start_time", "created_at"]) val startTime: String = "",
    @SerializedName(value = "endTime", alternate = ["end_time"]) val endTime: String? = null
)

data class CallHistoryResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("data") val data: List<CallHistory>?,
    @SerializedName("total") val total: Int?
)

// ==================== Contact Models ====================

data class Contact(
    @SerializedName("id") val id: Any? = null,
    @SerializedName("name") val name: String,
    @SerializedName("extension") val extension: String,
    @SerializedName("email") val email: String?,
    @SerializedName("department") val department: String?,
    @SerializedName("avatar_url") val avatarUrl: String?,
    @SerializedName("status") val status: String = "unknown" // online, offline, busy
)

data class ContactsResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("data") val data: List<Contact>?,
    @SerializedName("total") val total: Int?
)

// ==================== Location Models ====================

/** Legacy location update */
data class LocationUpdate(
    @SerializedName("user_id") val userId: String,
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("accuracy") val accuracy: Float,
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("address") val address: String?
)

data class LocationResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String?
)

/** POST /api/pbx/extension-locations */
data class ExtensionLocationRequest(
    @SerializedName("extension")   val extension: String,
    @SerializedName("latitude")    val latitude: Double,
    @SerializedName("longitude")   val longitude: Double,
    @SerializedName("accuracy_m")  val accuracyM: Double?,
    @SerializedName("altitude_m")  val altitudeM: Double? = null,
    @SerializedName("speed_mps")   val speedMps: Double? = null,
    @SerializedName("heading_deg") val headingDeg: Double? = null,
    @SerializedName("source")      val source: String = "gps",
    @SerializedName("device_id")   val deviceId: String?,
    @SerializedName("app_version") val appVersion: String?,
    @SerializedName("captured_at") val capturedAt: String?
)

/** Server response: {"ok":true,"action":"inserted","id":42,...} */
data class ExtensionLocationResponse(
    @SerializedName("ok")          val ok: Boolean,
    @SerializedName("action")      val action: String?,   // "inserted" or "updated"
    @SerializedName("id")          val id: Int?,
    @SerializedName("captured_at") val capturedAt: String?
)

/**
 * GET /api/pbx/extensions
 *
 * The route answers with the row as stored in `pbx_extensions`, so the agent's
 * name arrives as **`agent_name`**; the column is literally that name. A key is a
 * contract, and asking Gson for a key the server does not emit yields a silent
 * `null` with no error anywhere -- which is exactly how this field was broken:
 * `name` was declared, `agent_name` was sent, and every directory lookup came back
 * empty. Both spellings are accepted so the field survives either shape.
 */
data class PbxExtension(
    @SerializedName("extension")  val extension: String,
    @SerializedName("agent_name") val agentName: String?,
    @SerializedName("name")       val name: String?,
    // Deployments of the PBX have returned all of these spellings over time.
    // Keep the wire values separate: a Boolean presence flag must not be coerced
    // into a String, or Gson silently leaves status empty and every row becomes
    // "offline" even while the PBX console says it is online.
    @SerializedName(value = "status", alternate = ["presence", "registration_status", "state"])
    val status: String?,
    @SerializedName(value = "is_online", alternate = ["isOnline"])
    val isOnline: Boolean? = null,
    @SerializedName("online") val online: Boolean? = null,
    @SerializedName(value = "is_registered", alternate = ["isRegistered", "registered"])
    val isRegistered: Boolean? = null
) {
    /** The name to show for this extension, under either spelling. */
    val displayName: String?
        get() = agentName?.takeIf { it.isNotBlank() } ?: name?.takeIf { it.isNotBlank() }

    /** Live presence sent by the PBX, with Boolean flags taking precedence. */
    val displayStatus: String
        get() = when {
            isOnline == true || online == true || isRegistered == true -> "online"
            isOnline == false || online == false || isRegistered == false -> "offline"
            else -> status?.trim().orEmpty().ifBlank { "unknown" }
        }
}

// ==================== General API Response ====================

data class ApiResponse<T>(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String?,
    @SerializedName("data") val data: T?
)

// ==================== SIP State ====================

enum class SipRegistrationState {
    NONE,
    REGISTERING,
    REGISTERED,
    FAILED,
    CLEARED
}

enum class CallState {
    IDLE,
    OUTGOING,
    INCOMING,
    CONNECTED,
    PAUSED,
    ENDED,
    ERROR
}

data class SipCallInfo(
    val callId: String,
    val remoteAddress: String,
    val remoteName: String?,
    val direction: String, // "inbound" / "outbound"
    var state: CallState = CallState.IDLE,
    val startTime: Long = System.currentTimeMillis()
)
