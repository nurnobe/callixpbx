package com.callix.pbx.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.callix.pbx.CallixApplication
import com.callix.pbx.R
import timber.log.Timber

/**
 * Firebase Messaging Service Stub
 *
 * FCM push notifications are currently optional.
 * Incoming calls are handled in real-time via Linphone SIP foreground service.
 */
class FirebaseMessagingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("FirebaseMessagingService stub - FCM disabled")
        return START_NOT_STICKY
    }

    fun showMissedCallNotification(callerName: String, callerNumber: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(this, CallixApplication.CHANNEL_ID_NOTIFICATION)
            .setContentTitle("Missed Call")
            .setContentText("Missed call from $callerName ($callerNumber)")
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
