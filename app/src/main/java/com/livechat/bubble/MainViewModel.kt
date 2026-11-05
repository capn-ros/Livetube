package com.livechat.bubble

import android.app.Application
import android.content.Intent
import android.webkit.CookieManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainUi(
    val loggedIn: Boolean = false,
    val channels: List<Channel> = emptyList(),
    val followed: Set<String> = emptySet(),
    val serviceOn: Boolean = false,
    val loading: Boolean = false,
    val status: String = "",
)

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    private val store = AppStore(app)
    private val client = InnerTubeClient()

    private val _ui = MutableStateFlow(snapshot())
    val ui: StateFlow<MainUi> = _ui.asStateFlow()
    val live: StateFlow<Map<String, LiveInfo>> = LiveRegistry.live

    private fun snapshot() = MainUi(
        loggedIn = store.loggedIn,
        channels = store.channels,
        followed = store.followed,
        serviceOn = store.serviceEnabled,
    )

    fun reloadSession() {
        _ui.update { snapshot().copy(loading = it.loading, status = it.status) }
        if (store.loggedIn && store.channels.isEmpty() && !_ui.value.loading) refreshSubscriptions()
    }

    fun useCookie(cookie: String) {
        store.cookies = cookie
        reloadSession()
    }

    fun refreshSubscriptions() {
        if (_ui.value.loading) return
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, status = "Leyendo suscripciones…") }
            try {
                val fetched = client.fetchSubscriptions(store.cookies)
                if (fetched.isEmpty()) {
                    _ui.update {
                        it.copy(
                            loading = false,
                            status = "No encontré suscripciones. Podés agregar canales a mano con “+ Canal”.",
                        )
                    }
                    return@launch
                }
                val ids = fetched.map { it.id }.toSet()
                val fset = store.followed
                // los canales agregados a mano (seguidos y no suscriptos) se conservan
                val extra = store.channels.filter { it.id in fset && it.id !in ids }
                val merged = fetched + extra
                store.channels = merged
                _ui.update {
                    it.copy(loading = false, channels = merged, status = "${fetched.size} suscripciones")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, status = "Error: ${e.message}") }
            }
        }
    }

    fun toggleFollow(id: String) {
        val f = store.followed.toMutableSet()
        if (!f.add(id)) f.remove(id)
        store.followed = f
        _ui.update { it.copy(followed = f) }
    }

    fun addChannel(input: String) {
        if (input.isBlank()) return
        viewModelScope.launch {
            try {
                val ch = client.resolveChannel(input, store.cookies.ifEmpty { null })
                store.channels = listOf(ch) + store.channels.filter { it.id != ch.id }
                store.followed = store.followed + ch.id
                _ui.update {
                    it.copy(channels = store.channels, followed = store.followed, status = "Agregado: ${ch.title}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(status = "No pude agregar el canal: ${e.message}") }
            }
        }
    }

    /** Revisión manual de los canales seguidos (el servicio hace lo mismo solo, cada 2 minutos). */
    fun checkLiveNow() {
        if (_ui.value.loading) return
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, status = "Revisando quién está en vivo…") }
            val fset = store.followed
            var n = 0
            for (ch in store.channels.filter { it.id in fset }) {
                try {
                    val info = client.checkLive(ch.id)
                    LiveRegistry.live.update { m -> if (info != null) m + (ch.id to info) else m - ch.id }
                    if (info != null) n++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // se ignora: el próximo chequeo lo corrige
                }
                delay(300)
            }
            _ui.update { it.copy(loading = false, status = "En vivo ahora: $n") }
        }
    }

    fun setService(on: Boolean) {
        store.serviceEnabled = on
        val i = Intent(app, BubbleService::class.java)
        if (on) ContextCompat.startForegroundService(app, i) else app.stopService(i)
        _ui.update { it.copy(serviceOn = on) }
    }

    fun logout() {
        setService(false)
        store.logout()
        CookieManager.getInstance().removeAllCookies(null)
        LiveRegistry.live.value = emptyMap()
        _ui.value = snapshot()
    }
}
