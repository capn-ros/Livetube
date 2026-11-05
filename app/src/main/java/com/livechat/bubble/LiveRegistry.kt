package com.livechat.bubble

import kotlinx.coroutines.flow.MutableStateFlow

/** Estado compartido (app + servicio): qué canales están en vivo ahora. */
object LiveRegistry {
    val live = MutableStateFlow<Map<String, LiveInfo>>(emptyMap())
}
