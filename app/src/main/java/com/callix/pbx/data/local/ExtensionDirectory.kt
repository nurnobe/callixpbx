package com.callix.pbx.data.local

import android.content.Context
import com.callix.pbx.data.remote.RetrofitClient
import org.json.JSONObject
import timber.log.Timber

/**
 * The PBX's own extension directory: extension number -> the name of the person
 * who owns it.
 *
 * WHY A CACHE
 * An incoming call must be labelled the instant it rings. A network round trip at
 * that moment is exactly the wrong dependency -- the phone may be on a bad cell
 * edge, the request may take seconds, and the ring cannot wait. So the directory
 * is fetched when the app is already talking to the server (login, and app start)
 * and READ from memory during a call. [nameFor] therefore never blocks and never
 * touches the network.
 *
 * WHERE THE NAME ACTUALLY COMES FROM
 * Normally the SIP INVITE already carries the caller's name, and this cache is a
 * fallback -- it exists because a call placed from the webphone or by an older
 * client can arrive with no display name at all, and "Ext 101" is a worse answer
 * than the name the PBX already knows.
 *
 * NOT A SECRET
 * Extension numbers and the names attached to them are directory information, not
 * credentials, so this lives in an ordinary preferences file rather than the
 * encrypted store [PreferenceManager] uses for the SIP password and auth token.
 * It is keyed by user id so two accounts on one handset can never read each
 * other's directory.
 */
object ExtensionDirectory {

    private const val STORE = "callix_extension_directory"
    private const val KEY_PREFIX = "ext_names_"

    private fun store(context: Context) =
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    private fun keyFor(context: Context): String =
        KEY_PREFIX + PreferenceManager.getInstance(context).userId

    /**
     * The name the PBX holds for an extension, or null when it is not known.
     *
     * Reads the in-memory copy of the preferences file, so it is safe to call from
     * a ringing call path.
     */
    fun nameFor(context: Context, extension: String): String? {
        val ext = extension.trim()
        if (ext.isEmpty()) return null
        return try {
            val raw = store(context).getString(keyFor(context), null) ?: return null
            JSONObject(raw).optString(ext).takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            // A corrupt cache must never break a call; it only means no fallback name.
            Timber.w(t, "Extension directory cache could not be read")
            null
        }
    }

    /**
     * Fetch the directory and replace the cache.
     *
     * @return true when the server answered and the cache was written.
     */
    suspend fun refresh(context: Context): Boolean {
        val prefs = PreferenceManager.getInstance(context)
        val baseUrl = prefs.apiUrl
        val token = prefs.authToken
        if (baseUrl.isBlank() || token.isBlank()) {
            Timber.d("Extension directory not refreshed: not signed in")
            return false
        }

        return try {
            val response = RetrofitClient.getApiService(baseUrl)
                .getPbxExtensions("Bearer $token")

            if (!response.isSuccessful) {
                // Say the code out loud: a silent empty directory is indistinguishable
                // from "this tenant has no extensions".
                Timber.e("Extension directory request failed: HTTP %d", response.code())
                return false
            }

            val rows = response.body()?.data
            if (rows == null) {
                Timber.e("Extension directory response had no data payload")
                return false
            }

            val map = JSONObject()
            rows.forEach { row ->
                val ext = row.extension.trim()
                val name = row.displayName
                if (ext.isNotEmpty() && name != null) map.put(ext, name)
            }

            store(context).edit().putString(keyFor(context), map.toString()).apply()
            Timber.d("Extension directory cached: %d named extensions", map.length())
            true
        } catch (t: Throwable) {
            Timber.e(t, "Extension directory could not be refreshed")
            false
        }
    }
}