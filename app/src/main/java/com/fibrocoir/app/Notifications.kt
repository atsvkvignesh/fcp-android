package com.fibrocoir.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notifications {
    const val EXTRA_URL = "url"

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val id = ctx.getString(R.string.channel_id)
            if (nm.getNotificationChannel(id) == null) {
                val ch = NotificationChannel(
                    id, ctx.getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH
                )
                ch.description = "Alerts from Fibro Coir"
                ch.enableVibration(true)
                nm.createNotificationChannel(ch)
            }
        }
    }

    /** Shows a notification (used when a message arrives while the app is open). */
    fun show(ctx: Context, title: String, body: String, url: String?) {
        ensureChannel(ctx)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val open = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (!url.isNullOrBlank()) putExtra(EXTRA_URL, url)
        }
        val pi = PendingIntent.getActivity(
            ctx, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, ctx.getString(R.string.channel_id))
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(ctx, R.color.brand))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: SecurityException) {
        }
    }
}
