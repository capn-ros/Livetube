package com.livechat.bubble

import java.security.MessageDigest

/**
 * Header Authorization que usa la web de YouTube cuando hay sesión:
 *   SAPISIDHASH <ts>_<sha1("<ts> <SAPISID> <origin>")>
 * y sus variantes 1P / 3P. Se manda cada una para la cual exista la cookie.
 */
object SapisidAuth {
    const val ORIGIN = "https://www.youtube.com"

    fun cookieValue(cookieHeader: String, name: String): String? =
        cookieHeader.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter("=")
            ?.takeIf { it.isNotEmpty() }

    fun header(cookies: String, nowSec: Long = System.currentTimeMillis() / 1000): String? {
        val schemes = listOf(
            "SAPISID" to "SAPISIDHASH",
            "__Secure-1PAPISID" to "SAPISID1PHASH",
            "__Secure-3PAPISID" to "SAPISID3PHASH",
        )
        val parts = schemes.mapNotNull { (cookieName, scheme) ->
            val value = cookieValue(cookies, cookieName) ?: return@mapNotNull null
            "$scheme ${nowSec}_${sha1("$nowSec $value $ORIGIN")}"
        }
        return parts.joinToString(" ").ifEmpty { null }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
