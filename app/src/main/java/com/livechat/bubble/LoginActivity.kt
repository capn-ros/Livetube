package com.livechat.bubble

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Login con Google en un WebView, una sola vez: al volver a youtube.com se guardan las cookies
 * (cifradas) y se cierra. Si Google bloquea el login embebido, en la pantalla principal hay
 * la opción de pegar la cookie a mano.
 */
class LoginActivity : ComponentActivity() {
    private companion object {
        const val LOGIN_URL =
            "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true" +
                "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue" +
                "%26app%3Ddesktop%26hl%3Des%26next%3D%252F&hl=es"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = AppStore(this)
        setContent {
            LcbTheme {
                Column(Modifier.systemBarsPadding()) {
                    Text(
                        "Iniciá sesión con tu cuenta de Google. Se usa solo dentro de esta app.",
                        modifier = Modifier.padding(12.dp),
                    )
                    AndroidView(
                        factory = { ctx ->
                            buildWebView(ctx) { cookies ->
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
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(ctx: Context, onCookies: (String) -> Unit): WebView {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        return WebView(ctx).apply {
            cm.setAcceptThirdPartyCookies(this, true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Google rechaza el login en WebViews que se identifican como tales ("wv")
            settings.userAgentString = WebSettings.getDefaultUserAgent(ctx)
                .replace("; wv", "").replace("Version/4.0 ", "")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url != null && url.contains("youtube.com")) {
                        cm.getCookie("https://www.youtube.com")?.let(onCookies)
                    }
                }
            }
            loadUrl(LOGIN_URL)
        }
    }
}
