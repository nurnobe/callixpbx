package com.callix.pbx.service

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.callix.pbx.CallixApplication
import com.callix.pbx.R
import com.callix.pbx.data.remote.CallerIdentity
import com.callix.pbx.ui.call.CallerText
import com.callix.pbx.ui.main.MainActivity
import timber.log.Timber

/**
 * Raises the "missed call" notification.
 *
 * WHAT COUNTS AS MISSED
 * Only a call that really rang THIS device and was never answered. [SipManager]
 * is the only caller, and it refuses to report a call the user deliberately
 * declined, or an outbound call -- neither of which is a missed call.
 *
 * ONE NOTIFICATION, NOT ONE PER CALL
 * A second missed call replaces the body of the first rather than stacking, and
 * the title carries the running count.
 *
 * WHY THE STATE IS ON DISK
 * "The user has seen the missed calls" used to be an in-memory counter, which
 * meant two things went wrong. The count restarted at 1 every time the process
 * was restarted, so a phone left running for a week reported "1 missed call"
 * forever. And a miss reported late -- after the user had already opened the log
 * -- had nothing to compare against, so it raised the notification all over again
 * on top of a log the user was looking at. Both are cured by one persisted
 * watermark: the moment the user last acknowledged the log. A miss older than that
 * watermark is not news and is not alerted; a miss newer than it is.
 */
object MissedCallNotifier {

    private const val NOTIFICATION_ID = 1003
    private const val REQUEST_CODE = 4

    /** Ordinary preferences: this is UI bookkeeping, not a credential. */
    private const val STORE = "callix_missed_calls"
    private const val KEY_COUNT = "missed_count"
    private const val KEY_SEEN_AT = "seen_at_millis"

    private fun store(context: Context) =
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    /**
     * Post (or refresh) the missed-call notification.
     *
     * [name] and [number] arrive as the RAW SIP identity -- a displayed name, an
     * internal id such as "101%4009699800222", or an address. They are resolved
     * here by the shared [CallerIdentity]/[CallerText] pair, so the alert names the
     * caller the way a person would recognise them and never prints an internal id.
     * A caller the PBX could not label still gets an alert; nothing here invents an
     * identity.
     *
     * @param atMillis when the miss happened. A miss at or before the last
     *   acknowledged one has already been seen on screen and is dropped instead of
     *   re-alerted. The default is the moment of this report, which is the call's
     *   own end -- always after the ring it describes.
     */
    fun notifyMissed(
        context: Context,
        name: String?,
        number: String?,
        atMillis: Long = System.currentTimeMillis()
    ) {
        // Since Android 13 the notification permission is a runtime grant. Posting
        // without it is silently dropped by the system, so say so in the log rather
        // than appear to have alerted the user.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Timber.w("Missed call not shown: POST_NOTIFICATIONS not granted")
            return
        }

        val store = store(context)
        val seenAt = store.getLong(KEY_SEEN_AT, 0L)
        if (atMillis <= seenAt) {
            // The user has already opened the call log after this call rang. Raising
            // the notification now would put an alert in front of a screen that has
            // just shown the very call it is about.
            Timber.d("Missed call not alerted: already seen (seenAt=%d, missAt=%d)", seenAt, atMillis)
            return
        }

        val display = CallerIdentity.fromInbound(name, number)
        val label = CallerText.primary(context, display)

        // Persist before posting: if the process dies between the two, the count must
        // not silently fall back to a value that claims no call was ever missed.
        val count = store.getInt(KEY_COUNT, 0) + 1
        store.edit().putInt(KEY_COUNT, count).apply()

        val title = when {
            count > 1 -> context.getString(R.string.missed_call_title_many, count)
            else -> context.getString(R.string.missed_call_title, label)
        }

        val openHistory = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.TAB_CALLS)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            openHistory,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CallixApplication.CHANNEL_ID_MISSED)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.missed_call_open_history))
            .setSmallIcon(R.drawable.ic_call_missed)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setContentIntent(contentIntent)
            .setNumber(count)
            .setAutoCancel(true)

        // Show who it was when the title is the count, so a second missed call does
        // not hide the first one's identity.
        if (count > 1) builder.setSubText(label)

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
            Timber.d("Missed call notification posted (count=%d)", count)
        } catch (e: SecurityException) {
            // notify() is documented to throw when the permission is missing.
            Timber.w(e, "Missed call notification could not be posted")
        }
    }

    /**
     * Cancel the notification and record that the user has seen the missed calls.
     *
     * This is called by the call-log screen itself -- not only by the navigation
     * that happens to lead to it -- because the log is reachable from more than one
     * place, and a screen that is only marked "seen" on one of the routes to it is
     * precisely how the alert survives an open log.
     *
     * Safe to call repeatedly, and from any thread.
     */
    fun markSeen(context: Context) {
        val store = store(context)
        val now = System.currentTimeMillis()
        // Monotonic: a late call must not move the watermark backwards, or a miss it
        // already covered could be alerted a second time.
        val watermark = maxOf(store.getLong(KEY_SEEN_AT, 0L), now)
        store.edit()
            .putInt(KEY_COUNT, 0)
            .putLong(KEY_SEEN_AT, watermark)
            .apply()

        try {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            Timber.w(e, "Missed call notification could not be cancelled")
        }
    }
}