package com.fibrocoir.app

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * When the app is in the background, Android shows notifications by itself.
 * When the app is open, Firebase hands the message here and we show it.
 */
class FcmService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"] ?: getString(R.string.app_name)
        val body = message.notification?.body ?: message.data["body"] ?: return
        Notifications.show(this, title, body, message.data[Notifications.EXTRA_URL])
    }

    override fun onNewToken(token: String) {
        Topics.resubscribeAll(this)
    }
}
