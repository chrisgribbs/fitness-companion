package com.fitcompanion.poc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.fitcompanion.poc.ui.theme.Palette

/** Renders the simple Markdown AI replies use: paragraphs, bullets, numbered lists, headings, **bold**, *italic*. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, color: Color = Palette.Text, accent: Color = Palette.Volt) {
    val blocks = text.replace("\r", "").split(Regex("\n\\s*\n")).map { it.trim('\n') }.filter { it.isNotBlank() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block ->
            val lines = block.lines().filter { it.isNotBlank() }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                lines.forEach { raw ->
                    val line = raw.trim()
                    val bullet = Regex("^([-*•])\\s+(.*)").find(line)
                    val numbered = Regex("^(\\d+)[.)]\\s+(.*)").find(line)
                    val heading = Regex("^#{1,6}\\s+(.*)").find(line)
                    when {
                        heading != null -> Text(inline(heading.groupValues[1], accent), style = MaterialTheme.typography.titleMedium, color = color)
                        bullet != null || numbered != null -> Row {
                            Text(if (bullet != null) "•" else numbered!!.groupValues[1] + ".", style = MaterialTheme.typography.bodyLarge, color = accent)
                            androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
                            Text(inline((bullet ?: numbered)!!.groupValues[2], accent), style = MaterialTheme.typography.bodyLarge, color = color)
                        }
                        else -> Text(inline(line, accent), style = MaterialTheme.typography.bodyLarge, color = color)
                    }
                }
            }
        }
    }
}

/** **bold**, __bold__, *italic*, _italic_, `code`. Unmatched markers are shown as-is. */
fun inline(s: String, accent: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        val bold = when {
            s.startsWith("**", i) -> "**"
            s.startsWith("__", i) -> "__"
            else -> null
        }
        if (bold != null) {
            val end = s.indexOf(bold, i + 2)
            if (end > i + 2) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = accent)) { append(s.substring(i + 2, end)) }
                i = end + 2; continue
            }
        }
        val c = s[i]
        if ((c == '*' || c == '_') && i + 1 < s.length && s[i + 1] != ' ') {
            val end = s.indexOf(c, i + 1)
            if (end > i + 1 && (c == '*' || end + 1 >= s.length || !s[end + 1].isLetterOrDigit())) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.substring(i + 1, end)) }
                i = end + 1; continue
            }
        }
        if (c == '`') {
            val end = s.indexOf('`', i + 1)
            if (end > i) { withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(s.substring(i + 1, end)) }; i = end + 1; continue }
        }
        append(c); i++
    }
}
