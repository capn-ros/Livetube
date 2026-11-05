package com.livechat.bubble

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persistencia local: cookie cifrada, canales, canales seguidos y estado del servicio. */
class AppStore(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("lcb_data", Context.MODE_PRIVATE)
    private val legacy = ctx.applicationContext.getSharedPreferences("lcb_session", Context.MODE_PRIVATE)

    var cookies: String
        get() = sp.getString("cookies_enc", null)?.let { Crypto.decrypt(it) } ?: ""
        set(value) {
            val clean = value.replace("\r", " ").replace("\n", " ").trim()
                .let { if (it.startsWith("cookie:", ignoreCase = true)) it.substring(7).trim() else it }
            if (clean.isEmpty()) {
                sp.edit().remove("cookies_enc").apply()
            } else {
                sp.edit().putString("cookies_enc", Crypto.encrypt(clean)).apply()
            }
        }

    init {
        // migra la cookie en texto plano de la etapa 1
        val old = legacy.getString("cookies", null)
        if (!old.isNullOrEmpty()) {
            cookies = old
            legacy.edit().clear().apply()
        }
    }

    val loggedIn: Boolean get() = SapisidAuth.header(cookies) != null

    var channels: List<Channel>
        get() = sp.getString("channels", null)?.let { fromJson(it) } ?: emptyList()
        set(value) {
            sp.edit().putString("channels", toJson(value)).apply()
        }

    var followed: Set<String>
        get() = sp.getStringSet("followed", emptySet())?.toSet() ?: emptySet()
        set(value) {
            sp.edit().putStringSet("followed", HashSet(value)).apply()
        }

    var serviceEnabled: Boolean
        get() = sp.getBoolean("service_on", false)
        set(value) {
            sp.edit().putBoolean("service_on", value).apply()
        }

    fun logout() {
        cookies = ""
        channels = emptyList()
        followed = emptySet()
        serviceEnabled = false
    }

    private fun toJson(list: List<Channel>): String {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("t", it.title).put("a", it.avatarUrl ?: ""))
        }
        return arr.toString()
    }

    private fun fromJson(s: String): List<Channel> = try {
        val a = JSONArray(s)
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Channel(o.getString("id"), o.getString("t"), o.optString("a").ifEmpty { null })
        }
    } catch (e: Exception) {
        emptyList()
    }
}
