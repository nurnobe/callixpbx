package com.callix.pbx.data.model

import com.google.gson.annotations.SerializedName

/**
 * Wire + display models for the two screens that were changed most recently.
 *
 * WHY THIS FILE EXISTS SEPARATELY
 * The pre-existing [CallHistory] and [Contact] classes live in Models.kt, which
 * the device owner declined to share with the assistant. Rather than guess at
 * their exact source text, the models these two screens need are declared here
 * in full. Every field name below was read from the running server, not guessed:
 *
 *   GET /api/calls/history -> { success, data: [ { id, callerId, callerName,
 *       calleeId, calleeName, direction, status, duration, billsec, startTime,
 *       recordingUrl, hasRecording } ] }            (server/routes/callsHistory.js)
 *   GET /api/contacts      -> { success, data: [ { id, name, extension, email,
 *       department, status } ] }                    (server/routes/contacts.js)
 *
 * Both snake_case and camelCase spellings are accepted, because the history
 * route emits both. Gson instantiates via Unsafe, so a field with no matching key
 * is left at the JVM default (null / 0) and field initialisers DO NOT run --
 * therefore every string is nullable and every reader in the UI is null-safe.
 * That is the exact defect that previously left the call log's number and
 * timestamp blank while the JSON contained them.
 */

// ─────────────────────────── call history ───────────────────────────

data class CallRecord(
    @SerializedName("id") val id: String? = null,
    @SerializedName(value = "callerId", alternate = ["caller_id"]) val callerId: String? = null,
    @SerializedName(value = "callerName", alternate = ["caller_name"]) val callerName: String? = null,
    @SerializedName(value = "calleeId", alternate = ["callee_id"]) val calleeId: String? = null,
    @SerializedName(value = "calleeName", alternate = ["callee_name"]) val calleeName: String? = null,
    @SerializedName("direction") val direction: String? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("duration") val duration: Int = 0,
    @SerializedName(value = "startTime", alternate = ["start_time"]) val startTime: String? = null,
    /**
     * Path of the recording the CALLER IS ALLOWED TO HEAR, or null.
     *
     * The server only emits this for a row the caller owns AND that still carries
     * a recording_url, and it re-points it at the access-checked media route
     * (/api/pbx/media/file/<name>) instead of the raw /uploads path. The client
     * must never build this itself -- a client-built path is how one extension
     * ends up asking for another extension's file.
     */
    @SerializedName(value = "recordingUrl", alternate = ["recording_url"]) val recordingUrl: String? = null,
    @SerializedName("hasRecording") val hasRecording: Boolean = false
) {
    val isInbound: Boolean get() = direction?.trim()?.lowercase() == "inbound"

    /** The other party's number: whom this call was with, from this user's side. */
    val otherNumber: String
        get() = (if (isInbound) callerId else calleeId)?.trim().orEmpty()

    /** The other party's name, when the server could resolve one. */
    val otherName: String
        get() = (if (isInbound) callerName else calleeName)?.trim().orEmpty()

    /** A row is only playable when the server said so AND sent a usable path. */
    val canPlay: Boolean
        get() = hasRecording && !recordingUrl.isNullOrBlank()
}

data class CallRecordResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("data") val data: List<CallRecord> = emptyList()
)

// ─────────────────────────── contacts ───────────────────────────

/**
 * One row of the contacts screen, from either source.
 *
 * [number] is always what gets dialled. For a PBX contact that is the extension;
 * for a contact read off the phone it is the number as saved. [extension] is
 * non-blank only for PBX contacts, which is what draws the "Ext: 101" line.
 */
data class ContactRow(
    val key: String,
    val name: String,
    val number: String,
    val extension: String = "",
    val department: String = "",
    val status: String = "unknown",
    val isDevice: Boolean = false
)

/**
 * One row of GET /api/contacts, exactly as server/routes/contacts.js emits it:
 * { id, name, extension, email, department, status }.
 *
 * The extension is a String even though it looks numeric, and `status` is real
 * registration presence ("online" when the extension has a live, unexpired
 * contact, otherwise "offline") -- it is not a plan feature.
 */
data class ServerContact(
    @SerializedName("id") val id: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("extension") val extension: String? = null,
    @SerializedName("email") val email: String? = null,
    @SerializedName("department") val department: String? = null,
    @SerializedName("status") val status: String? = null
)

data class ServerContactResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("data") val data: List<ServerContact> = emptyList()
)
