package com.livechat.bubble

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Servicio en primer plano: cada 2 minutos revisa si los canales seguidos están en vivo
 * y publica / retira la burbuja de cada uno. El chat solo se lee mientras la burbuja está abierta.
 */
class BubbleService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private val client = InnerTubeClient()
    private val notified = HashSet<String>()
    private val misses = HashMap<String, Int>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            Notifier.SERVICE_ID,
            Notifier.serviceNotification(this),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        if (loop == null) loop = scope.launch { monitor() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        notified.forEach { Notifier.cancel(this, it) }
        notified.clear()
        LiveRegistry.live.value = emptyMap()
        super.onDestroy()
    }

    private suspend fun monitor() {
        val store = AppStore(this)
        while (true) {
            val fset = store.followed
            val followed = store.channels.filter { it.id in fset }
            for (ch in followed) {
                try {
                    onResult(ch, client.checkLive(ch.id))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // sin red o bloqueo temporal: se conserva el estado anterior
                }
                delay(500)
            }
            val stale = notified.filter { it !in fset }
            for (id in stale) {
                Notifier.cancel(this, id)
                notified.remove(id)
                LiveRegistry.live.update { m -> m - id }
            }
            delay(120_000)
        }
    }

    private suspend fun onResult(ch: Channel, info: LiveInfo?) {
        val current = LiveRegistry.live.value
        if (info != null) {
            misses.remove(ch.id)
            val changed = current[ch.id]?.videoId != info.videoId
            LiveRegistry.live.update { m -> m + (ch.id to info) }
            if (changed || ch.id !in notified) {
                val avatar = ch.avatarUrl?.let { ImageCache.get(it) }
                Notifier.postLive(this, ch, info, avatar)
                notified.add(ch.id)
            }
        } else if (current.containsKey(ch.id)) {
            val m = (misses[ch.id] ?: 0) + 1
            if (m >= 2) {
                misses.remove(ch.id)
                LiveRegistry.live.update { map -> map - ch.id }
                Notifier.cancel(this, ch.id)
                notified.remove(ch.id)
            } else {
                misses[ch.id] = m
            }
        }
    }
}
