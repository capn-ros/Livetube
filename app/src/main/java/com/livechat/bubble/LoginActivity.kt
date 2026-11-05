package com.livechat.bubble

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * Login con Google en un WebView, una sola vez: al volver a youtube.com se guardan las cookies
 * (cifradas) y se cierra. Si Google bloquea el login embebido, en la pantalla principal hay
 * la opción de pegar la cookie a mano.
 */
class LoginActivity : ComponentActivity() {
    private companion object {
        // Sin passive=true: con ese flag Google no muestra el formulario y vuelve directo a YouTube
        // sin sesión iniciada.
        const val LOGIN_URL =
            "https://accounts.google.com/ServiceLogin?service=youtube&hl=es" +
                "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue" +
                "%26app%3Ddesktop%26hl%3Des%26next%3D%252F"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = AppStore(this)
        setContent {
            LcbTheme {
                var error by remember { mutableStateOf<String?>(null) }
                Column(Modifier.systemBarsPadding().imePadding()) {
                    Text(
                        "Iniciá sesión con tu cuenta de Google. Se usa solo dentro de esta app.",
                        modifier = Modifier.padding(12.dp),
                    )
                    error?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    AndroidView(
                        factory = { ctx ->
                            buildWebView(ctx, onError = { error = it }) { cookies ->
                                if (SapisidAuth.header(cookies) != null) {
                                    store.cookies = cookies
                                    CookieManager.getInstance().flush()
                                    setResult(RESULT_OK)
                                    finish()
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                    TextButton(
                        onClick = { finish() },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) { Text("Cancelar (después podés pegar la cookie a mano)") }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(
        ctx: Context,
        onError: (String) -> Unit,
        onCookies: (String) -> Unit,
    ): WebView {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        return WebView(ctx).apply {
            cm.setAcceptThirdPartyCookies(this, true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Google rechaza el login en WebViews que se identifican como tales (\"wv\")
            settings.userAgentString = WebSettings.getDefaultUserAgent(ctx)
                .replace("; wv", "").replace("Version/4.0 ", "")
            // El WebView manda "X-Requested-With: <paquete de la app>" y Google lo usa para
            // detectar login embebido ("este navegador o app no es seguro"): se quita.
            try {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                    WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
                }
            } catch (_: Exception) {
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    val host = url?.let { Uri.parse(it).host } ?: return
                    if (host == "youtube.com" || host.endsWith(".youtube.com")) {
                        cm.getCookie("https://www.youtube.com")?.let(onCookies)
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        onError("No se pudo cargar la página (${error?.description}). Revisá tu conexión.")
                    }
                }
            }
            // arranca sin cookies viejas para no quedar en una sesión a medias
            cm.removeAllCookies { loadUrl(LOGIN_URL) }
        }
    }
}
