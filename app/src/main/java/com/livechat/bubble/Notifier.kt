package com.livechat.bubble

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/** Notificaciones: la del servicio y las "burbujas" de cada canal en vivo (estilo Messenger). */
object Notifier {
    private const val CH_SERVICE = "lcb_service"
    private const val CH_BUBBLES = "lcb_bubbles"
    const val SERVICE_ID = 1

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Servicio de monitoreo", NotificationManager.IMPORTANCE_LOW),
        )
        val bubbles = NotificationChannel(CH_BUBBLES, "Canales en vivo", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Burbujas de los canales seguidos que están en vivo"
            setSound(null, null)
            enableVibration(false)
            if (Build.VERSION.SDK_INT >= 29) setAllowBubbles(true)
        }
        nm.createNotificationChannel(bubbles)
    }

    fun serviceNotification(ctx: Context): Notification {
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_live)
            .setContentTitle("Vigilando canales en vivo")
            .setContentText("Tocá para abrir la app")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun notifId(channelId: String): Int = 1000 + (channelId.hashCode() and 0x7fffffff) % 100000

    fun postLive(ctx: Context, ch: Channel, info: LiveInfo, avatar: Bitmap?) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val icon = if (avatar != null) {
            IconCompat.createWithAdaptiveBitmap(avatar)
        } else {
            IconCompat.createWithResource(ctx, R.drawable.ic_live)
        }
        val person = Person.Builder().setName(ch.title).setKey(ch.id).setIcon(icon).setImportant(true).build()
        val me = Person.Builder().setName("Yo").build()

        // Las burbujas de conversación exigen un atajo "de larga vida" asociado a la notificación.
        val shortcutId = "ch_${ch.id}"
        val shortcut = ShortcutInfoCompat.Builder(ctx, shortcutId)
            .setLongLived(true)
            .setShortLabel(ch.title.take(25).ifEmpty { "Canal" })
            .setIcon(icon)
            .setPerson(person)
            .setLocusId(LocusIdCompat(shortcutId))
            .setIntent(Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW))
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(ctx, shortcut)

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getActivity(
            ctx,
            ch.id.hashCode(),
            ChatActivity.intent(ctx, info.videoId, ch.id, ch.title, info.title),
            flags,
        )
        val bubble = NotificationCompat.BubbleMetadata.Builder(pi, icon)
            .setDesiredHeight(640)
            .build()

        val style = NotificationCompat.MessagingStyle(me)
            .addMessage(info.title, System.currentTimeMillis(), person)

        val n = NotificationCompat.Builder(ctx, CH_BUBBLES)
            .setSmallIcon(R.drawable.ic_live)
            .setStyle(style)
            .setShortcutId(shortcutId)
            .setLocusId(LocusIdCompat(shortcutId))
            .addPerson(person)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pi)
            .setBubbleMetadata(bubble)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(ctx).notify(notifId(ch.id), n)
    }

    fun cancel(ctx: Context, channelId: String) {
        NotificationManagerCompat.from(ctx).cancel(notifId(channelId))
    }
}
