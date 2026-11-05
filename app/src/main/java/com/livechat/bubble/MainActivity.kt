package com.livechat.bubble

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { LcbTheme { MainScreen() } }
    }
}

private fun openBubbleSettings(ctx: Context) {
    val primary = if (Build.VERSION.SDK_INT >= 31) {
        Intent(Settings.ACTION_APP_NOTIFICATION_BUBBLE_SETTINGS)
    } else {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
    }.putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
    try {
        ctx.startActivity(primary)
    } catch (e: ActivityNotFoundException) {
        ctx.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName),
        )
    }
}

@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    var linkInput by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var addInput by remember { mutableStateOf("") }
    var showCookie by remember { mutableStateOf(false) }
    var cookieDraft by remember { mutableStateOf("") }

    val loginLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.reloadSession()
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        vm.setService(true)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.reloadSession() }

    val sorted = remember(ui.channels, ui.followed, live) {
        ui.channels.sortedWith(
            compareByDescending<Channel> { it.id in live }
                .thenByDescending { it.id in ui.followed }
                .thenBy { it.title.lowercase() },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("LiveTube", style = MaterialTheme.typography.titleLarge)

        // Probar un chat suelto por link (sirve con o sin sesión)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = linkInput,
                onValueChange = { linkInput = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Abrir chat por link o ID") },
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                extractVideoId(linkInput)?.let { ctx.startActivity(ChatActivity.intent(ctx, it, null, null, null)) }
            }) { Text("Abrir") }
        }

        if (!ui.loggedIn) {
            Text("Iniciá sesión con tu cuenta de Google para ver tus canales y poder escribir en el chat.")
            Button(onClick = { loginLauncher.launch(Intent(ctx, LoginActivity::class.java)) }) {
                Text("Iniciar sesión con Google")
            }
            TextButton(onClick = { showCookie = !showCookie }) { Text("Si el login no anda: pegar cookie") }
            if (showCookie) {
                OutlinedTextField(
                    value = cookieDraft,
                    onValueChange = { cookieDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text("Header Cookie de youtube.com") },
                )
                Button(onClick = {
                    vm.useCookie(cookieDraft)
                    cookieDraft = ""
                }) { Text("Usar cookie") }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = ui.serviceOn,
                    onCheckedChange = { on ->
                        val needsPermission = on && Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED
                        if (needsPermission) {
                            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            vm.setService(on)
                        }
                    },
                )
                Spacer(Modifier.width(8.dp))
                Text("Burbujas de canales en vivo", Modifier.weight(1f))
                TextButton(onClick = { openBubbleSettings(ctx) }) { Text("Ajustes") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::refreshSubscriptions, enabled = !ui.loading) { Text("Suscripciones") }
                OutlinedButton(onClick = vm::checkLiveNow, enabled = !ui.loading) { Text("Ver en vivo") }
                OutlinedButton(onClick = { showAdd = true }) { Text("+ Canal") }
            }
            Text("★ = seguir (el canal aparece como burbuja cuando está en vivo)", style = MaterialTheme.typography.labelSmall)

            LazyColumn(Modifier.weight(1f)) {
                items(sorted, key = { it.id }) { ch ->
                    val li = live[ch.id]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = li != null) {
                                li?.let { ctx.startActivity(ChatActivity.intent(ctx, it.videoId, ch.id, ch.title, it.title)) }
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RemoteImage(ch.avatarUrl, Modifier.size(40.dp).clip(CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(ch.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (li != null) {
                                Text(
                                    "● EN VIVO  ${li.title}",
                                    color = Color(0xFFE53935),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        TextButton(onClick = { vm.toggleFollow(ch.id) }) {
                            Text(if (ch.id in ui.followed) "★" else "☆")
                        }
                    }
                }
            }

            TextButton(onClick = vm::logout) { Text("Cerrar sesión") }
        }

        if (ui.status.isNotEmpty()) Text(ui.status, style = MaterialTheme.typography.labelMedium)
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Agregar canal") },
            text = {
                OutlinedTextField(
                    value = addInput,
                    onValueChange = { addInput = it },
                    singleLine = true,
                    label = { Text("@usuario, link o ID (UC…)") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.addChannel(addInput)
                    addInput = ""
                    showAdd = false
                }) { Text("Agregar") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancelar") } },
        )
    }
}
