package com.livechat.bubble

sealed interface Part {
    data class Plain(val text: String) : Part
    data class Emoji(val label: String, val url: String) : Part
}

data class ChatMessage(
    val id: String,
    val author: String,
    val text: String,
    val parts: List<Part> = emptyList(),
    val timestampUsec: Long = 0L,
    val isOwner: Boolean = false,
    val isModerator: Boolean = false,
    val isMember: Boolean = false,
    val superchatAmount: String? = null,
    val kind: Kind = Kind.TEXT,
) {
    enum class Kind { TEXT, SUPERCHAT, MEMBERSHIP }
}

data class ApiConfig(
    val apiKey: String,
    val clientVersion: String,
    val visitorData: String?,
)

/** Todo lo que hace falta para seguir hablando con el chat de un video. */
data class ChatSession(
    val videoId: String,
    val api: ApiConfig,
    val initialContinuation: String,
    val initialTimeoutMs: Long,
    val initialMessages: List<ChatMessage>,
    /** params para send_message, tal cual los entrega la página cuando hay sesión iniciada. */
    val sendParams: String?,
)

data class PollResult(
    val messages: List<ChatMessage>,
    val nextContinuation: String?,
    val timeoutMs: Long,
)

data class Channel(val id: String, val title: String, val avatarUrl: String?)

data class LiveInfo(val videoId: String, val title: String)
