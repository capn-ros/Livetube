package com.livechat.bubble

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

/** Pantalla del chat. También es lo que se muestra dentro de la burbuja. */
class ChatActivity : ComponentActivity() {
    companion object {
        private const val X_VIDEO = "video"
        private const val X_CHANNEL = "channel"
        private const val X_NAME = "name"
        private const val X_TITLE = "title"

        fun intent(ctx: Context, videoId: String, channelId: String?, name: String?, title: String?): Intent =
            Intent(ctx, ChatActivity::class.java).apply {
                // action + data únicos por video: cada burbuja tiene su propio PendingIntent
                action = "lcb.chat.$videoId"
                data = Uri.parse("lcb://chat/$videoId")
                putExtra(X_VIDEO, videoId)
                putExtra(X_CHANNEL, channelId)
                putExtra(X_NAME, name)
                putExtra(X_TITLE, title)
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val videoId = intent.getStringExtra(X_VIDEO)
        if (videoId == null) {
            finish()
            return
        }
        val name = intent.getStringExtra(X_NAME)
        val title = intent.getStringExtra(X_TITLE)
        setContent { LcbTheme { ChatScreen(videoId, name, title) } }
    }
}

class ChatViewModelFactory(
    private val app: Application,
    private val videoId: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(app, videoId) as T
}

@Composable
fun ChatScreen(videoId: String, channelName: String?, streamTitle: String?) {
    val app = LocalContext.current.applicationContext as Application
    val vm = viewModel<ChatViewModel>(key = videoId, factory = ChatViewModelFactory(app, videoId))
    val ui by vm.ui.collectAsStateWithLifecycle()

    // el chat solo se consulta mientras esto está visible (burbuja expandida o pantalla abierta)
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.start() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.pause() }

    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var stick by remember { mutableStateOf(true) }

    // si el usuario sube a leer, no lo arrastramos al final con cada mensaje nuevo
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canForward) -> if (scrolling) stick = !canForward }
    }
    LaunchedEffect(ui.messages.size) {
        if (stick && ui.messages.isNotEmpty()) listState.scrollToItem(ui.messages.lastIndex)
    }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .padding(8.dp),
    ) {
        Text(
            channelName ?: "Chat en vivo",
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
        )
        if (!streamTitle.isNullOrBlank()) {
            Text(
                streamTitle,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(ui.status, style = MaterialTheme.typography.labelSmall)

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(ui.messages) { MessageRow(it) }
            }
            if (!stick) {
                Button(
                    onClick = {
                        stick = true
                        scope.launch {
                            if (ui.messages.isNotEmpty()) listState.scrollToItem(ui.messages.lastIndex)
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) { Text("Ir al final") }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                label = { Text(if (ui.canSend) "Escribí un mensaje" else "Iniciá sesión para escribir") },
            )
            Button(
                enabled = ui.connected && ui.canSend && !ui.sending && draft.isNotBlank(),
                onClick = {
                    vm.send(draft)
                    draft = ""
                },
            ) { Text("Enviar") }
        }
    }
}
