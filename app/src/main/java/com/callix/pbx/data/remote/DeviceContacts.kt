package com.callix.pbx.data.remote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import timber.log.Timber

/** One number read off the handset's own address book. */
data class DeviceContact(val name: String, val number: String)

/**
 * Reads the contacts saved on THIS phone.
 *
 * Deliberately a plain ContentResolver read rather than a full contacts library:
 * the app needs a name and a dialable number and nothing else, and the fewer
 * columns it asks for the less it holds in memory on a handset with a large
 * address book. The query must not run on the main thread -- callers do it from
 * Dispatchers.IO.
 *
 * READ_CONTACTS is already declared in AndroidManifest.xml, so nothing here
 * changes the manifest; [hasPermission] is what tells the screen whether it may
 * ask the user.
 */
object DeviceContacts {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Every saved number, de-duplicated, with the name it is filed under.
     *
     * Returns an empty list when permission is missing -- it never invents rows.
     * A failure of the provider itself is **thrown, not swallowed**: "this phone
     * has no contacts" and "I could not read this phone's contacts" are different
     * facts, and collapsing them would show the user a confident empty list that
     * is simply wrong. The screen catches it and says which one happened.
     */
    fun load(context: Context): List<DeviceContact> {
        if (!hasPermission(context)) return emptyList()

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val out = LinkedHashMap<String, DeviceContact>()

        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )?.use { cursor ->
            val nameIdx =
                cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx =
                cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            if (nameIdx < 0 || numberIdx < 0) return emptyList()

            while (cursor.moveToNext()) {
                val rawName = cursor.getString(nameIdx).orEmpty().trim()
                val number = normalize(cursor.getString(numberIdx))
                if (number.isEmpty()) continue
                // The same number is often filed under two spellings of one
                // name; keying on name+number keeps genuine duplicates out
                // without collapsing two different people who share a line.
                val key = rawName.lowercase() + "|" + number
                if (out.containsKey(key)) continue
                out[key] = DeviceContact(rawName.ifBlank { number }, number)
            }
        }
        return out.values.toList()
    }

    /**
     * Reduce a stored number to something that can actually be dialled: digits,
     * with a leading "+" preserved because that is what makes an international
     * number international. Spaces, dashes, brackets and any SIP-style suffix are
     * dropped -- a number the dialer cannot use is worse than no number.
     */
    private fun normalize(raw: String?): String {
        val text = raw.orEmpty().trim()
        if (text.isEmpty()) return ""
        val hasPlus = text.startsWith("+")
        val digits = text.filter { it.isDigit() }
        if (digits.isEmpty()) return ""
        return if (hasPlus) "+$digits" else digits
    }
}
