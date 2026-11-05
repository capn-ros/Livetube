package com.livechat.bubble

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

data class ChatUi(
    val status: String = "Conectando…",
    val connected: Boolean = false,
    val canSend: Boolean = false,
    val sending: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
)

/** Un chat de un video. Se lee solo mientras la pantalla / burbuja está visible. */
class ChatViewModel(app: Application, private val videoId: String) : AndroidViewModel(app) {
    private val client = InnerTubeClient()
    private val store = AppStore(app)

    private val _ui = MutableStateFlow(ChatUi(canSend = store.loggedIn))
    val ui: StateFlow<ChatUi> = _ui

    private var job: Job? = null
    private var session: ChatSession? = null
    private val seen = HashSet<String>()

    fun start() {
        if (job?.isActive == true) return
        job = viewModelScope.launch { run() }
    }

    fun pause() {
        job?.cancel()
        job = null
        _ui.update { it.copy(connected = false, status = "En pausa") }
    }

    private suspend fun run() {
        try {
            _ui.update { it.copy(status = "Conectando…") }
            val cookies = store.cookies.ifEmpty { null }
            val s = client.openChat(videoId, cookies)
            session = s
            val logged = store.loggedIn
            _ui.update {
                it.copy(
                    connected = true,
                    canSend = logged,
                    status = when {
                        s.sendParams != null -> "En vivo"
                        logged -> "En vivo (envío sin confirmar)"
                        else -> "En vivo (solo lectura)"
                    },
                )
            }
            add(s.initialMessages)

            var cont: String? = s.initialContinuation
            var wait = s.initialTimeoutMs
            var failures = 0
            while (cont != null) {
                delay(wait.coerceIn(1000L, 10000L))
                try {
                    val r = client.poll(s, cont, cookies)
                    failures = 0
                    add(r.messages)
                    cont = r.nextContinuation
                    wait = r.timeoutMs
                } catch (e: IOException) {
                    failures++
                    if (failures >= 5) throw e
                    _ui.update { it.copy(status = "Reintentando… ($failures)") }
                    wait = 3000L
                }
            }
            _ui.update { it.copy(connected = false, status = "El chat terminó") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _ui.update { it.copy(connected = false, status = "Error: ${e.message}") }
        }
    }

    fun send(text: String) {
        val s = session ?: return
        val t = text.trim()
        if (t.isEmpty()) return
        viewModelScope.launch {
            _ui.update { it.copy(sending = true) }
            try {
                val echoed = client.sendMessage(s, t, store.cookies)
                add(echoed)
                _ui.update { it.copy(sending = false, status = "Enviado") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(sending = false, status = "No se pudo enviar: ${e.message}") }
            }
        }
    }

    private fun add(list: List<ChatMessage>) {
        val fresh = list.filter { seen.add(it.id) }
        if (fresh.isEmpty()) return
        _ui.update { it.copy(messages = (it.messages + fresh).takeLast(600)) }
    }
}
