package com.livechat.bubble

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

// Sin seguir redirecciones solas: se siguen a mano verificando que el host sea de YouTube,
// para que la cookie nunca viaje a otro dominio.
private fun newHttp(): OkHttpClient =
    OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()

/**
 * Cliente mínimo del "InnerTube" de YouTube (la API interna que usa la web).
 * No oficial: si YouTube cambia el esquema, hay que ajustar los parsers de abajo.
 */
class InnerTubeClient(private val http: OkHttpClient = newHttp()) {

    private companion object {
        const val ORIGIN = SapisidAuth.ORIGIN
        const val UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"
        val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    // ------------------------------------------------------------------ chat

    suspend fun openChat(videoId: String, cookies: String?): ChatSession = withContext(Dispatchers.IO) {
        val html = get("$ORIGIN/live_chat?is_popout=1&v=$videoId", cookies)
        val api = configFrom(html)

        val data = extractInitialData(html) ?: error("No encontré ytInitialData en la página")
        val chat = data.optJSONObject("contents")?.optJSONObject("liveChatRenderer")
            ?: error("Este video no tiene chat en vivo disponible")

        val (token, timeout) = parseContinuation(chat.optJSONArray("continuations"))
            ?: error("No encontré el token de continuación del chat")

        val sendParams = chat.optJSONObject("actionPanel")
            ?.optJSONObject("liveChatMessageInputRenderer")
            ?.optJSONObject("sendButton")
            ?.optJSONObject("buttonRenderer")
            ?.optJSONObject("serviceEndpoint")
            ?.optJSONObject("sendLiveChatMessageEndpoint")
            ?.optString("params")
            ?.takeIf { it.isNotEmpty() }

        ChatSession(
            videoId = videoId,
            api = api,
            initialContinuation = token,
            initialTimeoutMs = timeout,
            initialMessages = parseActions(chat.optJSONArray("actions")),
            sendParams = sendParams,
        )
    }

    suspend fun poll(session: ChatSession, continuation: String, cookies: String?): PollResult =
        withContext(Dispatchers.IO) {
            val body = baseBody(session.api).put("continuation", continuation)
            val resp = post("live_chat/get_live_chat", session.api, chatReferer(session.videoId), body, cookies)
            val lcc = resp.optJSONObject("continuationContents")?.optJSONObject("liveChatContinuation")
                ?: return@withContext PollResult(emptyList(), null, 0L)
            val next = parseContinuation(lcc.optJSONArray("continuations"))
            PollResult(parseActions(lcc.optJSONArray("actions")), next?.first, next?.second ?: 3000L)
        }

    suspend fun sendMessage(session: ChatSession, text: String, cookies: String): List<ChatMessage> =
        withContext(Dispatchers.IO) {
            require(SapisidAuth.header(cookies) != null) { "Faltan cookies de sesión (SAPISID)" }
            val params = session.sendParams
                ?: SendParams.build(fetchChannelId(session.videoId, cookies), session.videoId)
            val body = baseBody(session.api)
                .put(
                    "richMessage",
                    JSONObject().put("textSegments", JSONArray().put(JSONObject().put("text", text))),
                )
                .put("clientMessageId", "lcb-" + UUID.randomUUID())
                .put("params", params)
            val resp = post("live_chat/send_message", session.api, chatReferer(session.videoId), body, cookies)
            parseActions(resp.optJSONArray("actions"))
        }

    // --------------------------------------------------------------- canales

    /** Lee /feed/channels (tus suscripciones) y sigue la paginación. */
    suspend fun fetchSubscriptions(cookies: String): List<Channel> = withContext(Dispatchers.IO) {
        val html = get("$ORIGIN/feed/channels", cookies)
        val api = configFrom(html)
        val root = extractInitialData(html) ?: error("No pude leer la página de suscripciones")

        val all = LinkedHashMap<String, Channel>()
        val first = collectChannels(root)
        first.first.forEach { all.putIfAbsent(it.id, it) }
        var token = first.second
        var pages = 0
        while (token != null && pages < 30) {
            val body = baseBody(api).put("continuation", token)
            val resp = post("browse", api, "$ORIGIN/feed/channels", body, cookies)
            val page = collectChannels(resp)
            page.first.forEach { all.putIfAbsent(it.id, it) }
            token = page.second
            pages++
        }
        all.values.toList()
    }

    /** Resuelve @usuario, link o ID UC... a un canal (sirve para agregar canales a mano). */
    suspend fun resolveChannel(input: String, cookies: String?): Channel = withContext(Dispatchers.IO) {
        val t = input.trim()
        val url = when {
            Regex("^UC[A-Za-z0-9_-]{22}$").matches(t) -> "$ORIGIN/channel/$t"
            t.startsWith("@") -> "$ORIGIN/$t"
            t.startsWith("http") -> t
            else -> "$ORIGIN/@$t"
        }
        val html = get(url, cookies)
        val id = Regex("<link rel=\"canonical\" href=\"https://www\\.youtube\\.com/channel/(UC[A-Za-z0-9_-]{22})\"")
            .find(html)?.groupValues?.get(1)
            ?: Regex("\"channelId\":\"(UC[A-Za-z0-9_-]{22})\"").find(html)?.groupValues?.get(1)
            ?: error("No encontré ese canal")
        val title = Regex("<meta property=\"og:title\" content=\"([^\"]*)\"").find(html)
            ?.groupValues?.get(1)?.let { unescapeHtml(it) } ?: id
        val avatar = Regex("<meta property=\"og:image\" content=\"([^\"]*)\"").find(html)?.groupValues?.get(1)
        Channel(id, title, avatar)
    }

    /** ¿Está en vivo ahora? Mira la página pública /live del canal (sin cookies). */
    suspend fun checkLive(channelId: String): LiveInfo? = withContext(Dispatchers.IO) {
        val html = get("$ORIGIN/channel/$channelId/live", null)
        val canonical = Regex("<link rel=\"canonical\" href=\"https://www\\.youtube\\.com/watch\\?v=([A-Za-z0-9_-]{11})\"")
            .find(html)?.groupValues?.get(1)
        val fromDetails = Regex("\"videoDetails\":\\{\"videoId\":\"([A-Za-z0-9_-]{11})\"[^{}]*?\"isLive\":true")
            .find(html)?.groupValues?.get(1)
        val videoId = fromDetails
            ?: if (canonical != null && html.contains("\"isLiveNow\":true")) canonical else null
        if (videoId == null) return@withContext null
        val title = Regex("<meta property=\"og:title\" content=\"([^\"]*)\"").find(html)
            ?.groupValues?.get(1)?.let { unescapeHtml(it) } ?: "En vivo"
        LiveInfo(videoId, title)
    }

    // ------------------------------------------------------------------ http

    private fun chatReferer(videoId: String) = "$ORIGIN/live_chat?is_popout=1&v=$videoId"

    private fun requireYoutube(url: String) {
        val host = url.toHttpUrlOrNull()?.host ?: throw IOException("URL inválida")
        if (host != "youtube.com" && !host.endsWith(".youtube.com")) {
            throw IOException("Host no permitido: $host")
        }
    }

    private fun baseBody(api: ApiConfig): JSONObject =
        JSONObject().put(
            "context",
            JSONObject().put(
                "client",
                JSONObject().apply {
                    put("clientName", "WEB")
                    put("clientVersion", api.clientVersion)
                    put("hl", "es")
                    api.visitorData?.let { put("visitorData", it) }
                },
            ),
        )

    private fun get(url: String, cookies: String?): String {
        var current = url
        repeat(6) {
            requireYoutube(current)
            val rb = Request.Builder().url(current)
                .header("User-Agent", UA)
                .header("Accept-Language", "es-AR,es;q=0.9")
            if (!cookies.isNullOrBlank()) rb.header("Cookie", cookies)
            http.newCall(rb.build()).execute().use { r ->
                if (r.isRedirect) {
                    current = r.header("Location")?.let { r.request.url.resolve(it)?.toString() }
                        ?: throw IOException("Redirección sin destino")
                    return@repeat
                }
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                return r.body?.string().orEmpty()
            }
        }
        throw IOException("Demasiadas redirecciones")
    }

    private fun post(path: String, api: ApiConfig, referer: String, body: JSONObject, cookies: String?): JSONObject {
        val url = "$ORIGIN/youtubei/v1/$path?key=${api.apiKey}&prettyPrint=false"
        requireYoutube(url)
        val rb = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Origin", ORIGIN)
            .header("Referer", referer)
            .header("X-Youtube-Client-Name", "1")
            .header("X-Youtube-Client-Version", api.clientVersion)
            .post(body.toString().toRequestBody(JSON_TYPE))
        if (!cookies.isNullOrBlank()) {
            rb.header("Cookie", cookies)
            SapisidAuth.header(cookies)?.let {
                rb.header("Authorization", it)
                rb.header("X-Goog-AuthUser", "0")
                rb.header("X-Origin", ORIGIN)
            }
        }
        http.newCall(rb.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}: ${text.take(300)}")
            return JSONObject(text)
        }
    }

    private fun fetchChannelId(videoId: String, cookies: String?): String {
        val html = get("$ORIGIN/watch?v=$videoId", cookies)
        return Regex("\"videoDetails\":\\{[^}]*?\"channelId\":\"(UC[A-Za-z0-9_-]{22})\"")
            .find(html)?.groupValues?.get(1)
            ?: Regex("\"channelId\":\"(UC[A-Za-z0-9_-]{22})\"").find(html)?.groupValues?.get(1)
            ?: error("No pude obtener el channelId del video")
    }

    // --------------------------------------------------------------- parsers

    private fun configFrom(html: String): ApiConfig {
        val key = Regex("\"INNERTUBE_API_KEY\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            ?: error("No encontré la API key en la página")
        val version = Regex("\"INNERTUBE_CONTEXT_CLIENT_VERSION\":\"([^\"]+)\"")
            .find(html)?.groupValues?.get(1) ?: "2.20240101.00.00"
        val visitor = Regex("\"VISITOR_DATA\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)
        return ApiConfig(key, version, visitor)
    }

    /** Saca el JSON `ytInitialData` de la página recorriendo llaves (respeta strings). */
    private fun extractInitialData(html: String): JSONObject? {
        val m = Regex("ytInitialData(?:\"\\])?\\s*=\\s*\\{").find(html) ?: return null
        val start = m.range.last
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until html.length) {
            val c = html[i]
            if (inStr) {
                if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return JSONObject(html.substring(start, i + 1))
                    }
                    else -> {}
                }
            }
        }
        return null
    }

    /** Recorre todo el JSON y avisa por cada objeto hijo con el nombre de su clave. */
    private fun walk(node: Any?, visit: (String, JSONObject) -> Unit) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = node.opt(k)
                    if (v is JSONObject) visit(k, v)
                    walk(v, visit)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), visit)
        }
    }

    private fun fixUrl(u: String): String = if (u.startsWith("//")) "https:$u" else u

    private fun collectChannels(root: JSONObject): Pair<List<Channel>, String?> {
        val out = LinkedHashMap<String, Channel>()
        var token: String? = null
        walk(root) { key, obj ->
            when (key) {
                "channelRenderer", "gridChannelRenderer" -> {
                    val id = obj.optString("channelId")
                    if (id.startsWith("UC")) {
                        val title = obj.optJSONObject("title")?.optString("simpleText").orEmpty().ifEmpty { id }
                        val thumb = obj.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                            ?.optJSONObject(0)?.optString("url")
                        out.putIfAbsent(id, Channel(id, title, thumb?.let { fixUrl(it) }))
                    }
                }
                "lockupViewModel" -> {
                    if (obj.optString("contentType").contains("CHANNEL")) {
                        val id = obj.optString("contentId")
                        if (id.startsWith("UC")) {
                            val title = obj.optJSONObject("metadata")
                                ?.optJSONObject("lockupMetadataViewModel")
                                ?.optJSONObject("title")?.optString("content").orEmpty().ifEmpty { id }
                            val img = obj.optJSONObject("contentImage")
                                ?.optJSONObject("decoratedAvatarViewModel")
                                ?.optJSONObject("avatar")
                                ?.optJSONObject("avatarViewModel")
                                ?.optJSONObject("image")
                                ?.optJSONArray("sources")
                                ?.optJSONObject(0)?.optString("url")
                            out.putIfAbsent(id, Channel(id, title, img?.let { fixUrl(it) }))
                        }
                    }
                }
                "continuationItemRenderer" -> {
                    val t = obj.optJSONObject("continuationEndpoint")
                        ?.optJSONObject("continuationCommand")?.optString("token")
                    if (!t.isNullOrEmpty()) token = t
                }
                else -> {}
            }
        }
        return out.values.toList() to token
    }

    private fun unescapeHtml(s: String): String =
        s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">")

    private fun parseContinuation(arr: JSONArray?): Pair<String, Long>? {
        if (arr == null) return null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            for (key in listOf("invalidationContinuationData", "timedContinuationData", "reloadContinuationData")) {
                val d = o.optJSONObject(key) ?: continue
                val token = d.optString("continuation")
                if (token.isNotEmpty()) return token to d.optLong("timeoutMs", 3000L)
            }
        }
        return null
    }

    private fun parseActions(arr: JSONArray?): List<ChatMessage> {
        if (arr == null) return emptyList()
        val out = ArrayList<ChatMessage>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i)
                ?.optJSONObject("addChatItemAction")
                ?.optJSONObject("item") ?: continue
            item.optJSONObject("liveChatTextMessageRenderer")?.let {
                out.add(parseMessage(it, ChatMessage.Kind.TEXT, null))
            }
            item.optJSONObject("liveChatPaidMessageRenderer")?.let {
                val amount = it.optJSONObject("purchaseAmountText")?.optString("simpleText")
                out.add(parseMessage(it, ChatMessage.Kind.SUPERCHAT, amount))
            }
            item.optJSONObject("liveChatMembershipItemRenderer")?.let {
                out.add(parseMembership(it))
            }
        }
        return out
    }

    private fun parseMessage(r: JSONObject, kind: ChatMessage.Kind, amount: String?): ChatMessage {
        var owner = false
        var mod = false
        var member = false
        val badges = r.optJSONArray("authorBadges")
        if (badges != null) {
            for (i in 0 until badges.length()) {
                val b = badges.optJSONObject(i)?.optJSONObject("liveChatAuthorBadgeRenderer") ?: continue
                when (b.optJSONObject("icon")?.optString("iconType")) {
                    "OWNER" -> owner = true
                    "MODERATOR" -> mod = true
                }
                if (b.has("customThumbnail")) member = true
            }
        }
        val (text, parts) = parseRuns(r.optJSONObject("message"))
        return buildMessage(r, text, parts, kind, amount, owner, mod, member)
    }

    private fun parseMembership(r: JSONObject): ChatMessage {
        val h = r.optJSONObject("headerSubtext")
        val (text, parts) = if (h == null) {
            "" to emptyList<Part>()
        } else {
            val simple = h.optString("simpleText")
            if (simple.isNotEmpty()) simple to listOf<Part>(Part.Plain(simple)) else parseRuns(h)
        }
        return buildMessage(r, text, parts, ChatMessage.Kind.MEMBERSHIP, null, false, false, true)
    }

    private fun buildMessage(
        r: JSONObject, text: String, parts: List<Part>, kind: ChatMessage.Kind, amount: String?,
        owner: Boolean, mod: Boolean, member: Boolean,
    ): ChatMessage {
        val author = r.optJSONObject("authorName")?.optString("simpleText").orEmpty()
        val ts = r.optString("timestampUsec").toLongOrNull() ?: 0L
        val id = r.optString("id").ifEmpty { "$ts-$author-${text.hashCode()}" }
        return ChatMessage(id, author, text, parts, ts, owner, mod, member, amount, kind)
    }

    /** Devuelve el texto plano y las partes (texto / emoji personalizado con su imagen). */
    private fun parseRuns(msg: JSONObject?): Pair<String, List<Part>> {
        val runs = msg?.optJSONArray("runs") ?: return "" to emptyList()
        val sb = StringBuilder()
        val parts = ArrayList<Part>()
        for (i in 0 until runs.length()) {
            val run = runs.optJSONObject(i) ?: continue
            if (run.has("text")) {
                val s = run.optString("text")
                sb.append(s)
                parts.add(Part.Plain(s))
            } else {
                val e = run.optJSONObject("emoji") ?: continue
                if (e.optBoolean("isCustomEmoji")) {
                    val label = e.optJSONArray("shortcuts")?.optString(0).orEmpty().ifEmpty { ":emoji:" }
                    val url = e.optJSONObject("image")?.optJSONArray("thumbnails")
                        ?.optJSONObject(0)?.optString("url")
                    sb.append(label)
                    if (!url.isNullOrEmpty()) parts.add(Part.Emoji(label, fixUrl(url))) else parts.add(Part.Plain(label))
                } else {
                    val s = e.optString("emojiId")
                    sb.append(s)
                    parts.add(Part.Plain(s))
                }
            }
        }
        return sb.toString() to parts
    }
}
