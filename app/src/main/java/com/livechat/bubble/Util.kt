package com.livechat.bubble

fun extractVideoId(input: String): String? {
    val t = input.trim()
    if (Regex("^[A-Za-z0-9_-]{11}$").matches(t)) return t
    return Regex("(?:v=|youtu\\.be/|/live/|/shorts/|embed/)([A-Za-z0-9_-]{11})")
        .find(t)?.groupValues?.get(1)
}
