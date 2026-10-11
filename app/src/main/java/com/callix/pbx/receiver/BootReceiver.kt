package com.callix.pbx.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.callix.pbx.util.KeepAlive
import timber.log.Timber

/**
 * Brings the SIP service back up after the device restarts, and after the app is
 * updated.
 *
 * A SIP phone that has to be opened before it can receive a call is not a phone,
 * so the service is restarted without the user doing anything. Both events matter:
 * BOOT_COMPLETED covers a restart, MY_PACKAGE_REPLACED covers an upgrade, which
 * otherwise leaves the service down until the user notices calls have stopped
 * arriving. QUICKBOOT_POWERON is the spelling some vendors use for "boot finished".
 *
 * [KeepAlive.startSipService] refuses to start anything for a signed-out user, so
 * this cannot put a permanent "ready to receive calls" notification on a phone
 * that has never been signed in.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_QUICKBOOT_POWERON -> {
                Timber.d("Service restart requested by %s", intent.action)
                KeepAlive.startSipService(context)
            }
            else -> Timber.d("BootReceiver ignoring %s", intent.action)
        }
    }

    private companion object {
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
