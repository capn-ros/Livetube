package com.livechat.bubble

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

@Composable
fun LcbTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize(), content = content)
    }
}

@Composable
fun MessageRow(m: ChatMessage) {
    val nameColor = when {
        m.isOwner -> Color(0xFFFFB300)
        m.isModerator -> Color(0xFF5E84F1)
        m.isMember -> Color(0xFF2BA640)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val emojis = m.parts.filterIsInstance<Part.Emoji>()
    val inline = emojis.associate { e ->
        "e:${e.url}" to InlineTextContent(
            Placeholder(1.3.em, 1.3.em, PlaceholderVerticalAlign.TextCenter),
        ) { _ -> RemoteImage(e.url, Modifier.fillMaxSize()) }
    }
    val text = buildAnnotatedString {
        if (m.superchatAmount != null) {
            withStyle(SpanStyle(color = Color(0xFFE91E63), fontWeight = FontWeight.Bold)) {
                append("${m.superchatAmount} ")
            }
        }
        withStyle(SpanStyle(color = nameColor, fontWeight = FontWeight.Bold)) { append(m.author) }
        append("  ")
        if (m.parts.isEmpty()) {
            append(m.text)
        } else {
            m.parts.forEach { p ->
                when (p) {
                    is Part.Plain -> append(p.text)
                    is Part.Emoji -> appendInlineContent("e:${p.url}", p.label)
                }
            }
        }
    }
    Text(text, inlineContent = inline, modifier = Modifier.padding(vertical = 2.dp))
}
