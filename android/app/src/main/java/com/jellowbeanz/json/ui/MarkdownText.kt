package com.jellowbeanz.json.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * A small, dependency-free markdown renderer covering what the model actually emits:
 * headers, **bold**, *italic*, `inline code`, fenced ```code blocks```, and bullet / numbered lists.
 */
@Composable
fun MarkdownText(text: String, color: Color, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseBlocks(text) }
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Code -> CodeBlock(block.code, block.lang)
                is MdBlock.Header -> Text(
                    inline(block.text, codeBg),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    color = color,
                )
                is MdBlock.Bullet -> Row {
                    Text("•  ", style = MaterialTheme.typography.bodyLarge, color = color)
                    Text(inline(block.text, codeBg), style = MaterialTheme.typography.bodyLarge, color = color)
                }
                is MdBlock.Numbered -> Row {
                    Text("${block.num}.  ", style = MaterialTheme.typography.bodyLarge, color = color)
                    Text(inline(block.text, codeBg), style = MaterialTheme.typography.bodyLarge, color = color)
                }
                is MdBlock.Paragraph -> Text(
                    inline(block.text, codeBg),
                    style = MaterialTheme.typography.bodyLarge,
                    color = color,
                )
            }
        }
    }
}

@Composable
private fun CodeBlock(code: String, lang: String) {
    val c = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    Surface(color = c.surfaceVariant, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, c.outline)) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    lang.ifBlank { "code" },
                    style = MaterialTheme.typography.labelSmall,
                    color = c.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.ContentCopy, "Copy code", tint = c.onSurfaceVariant, modifier = Modifier.size(15.dp))
                }
            }
            Box(Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                Text(
                    code,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = c.onBackground,
                )
            }
        }
    }
}

// ---------- parsing ----------

private sealed class MdBlock {
    data class Paragraph(val text: String) : MdBlock()
    data class Header(val level: Int, val text: String) : MdBlock()
    data class Bullet(val text: String) : MdBlock()
    data class Numbered(val num: Int, val text: String) : MdBlock()
    data class Code(val code: String, val lang: String) : MdBlock()
}

private val NUMBERED = Regex("^(\\d+)\\.\\s+(.*)")

private fun parseBlocks(src: String): List<MdBlock> {
    val lines = src.split("\n")
    val blocks = mutableListOf<MdBlock>()
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) blocks.add(MdBlock.Paragraph(para.toString().trim()))
        para.setLength(0)
    }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trimStart()
        when {
            t.startsWith("```") -> {
                flush()
                val lang = t.removePrefix("```").trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code.appendLine(lines[i]); i++
                }
                blocks.add(MdBlock.Code(code.toString().trimEnd('\n'), lang))
            }
            t.startsWith("### ") -> { flush(); blocks.add(MdBlock.Header(3, t.removePrefix("### "))) }
            t.startsWith("## ") -> { flush(); blocks.add(MdBlock.Header(2, t.removePrefix("## "))) }
            t.startsWith("# ") -> { flush(); blocks.add(MdBlock.Header(1, t.removePrefix("# "))) }
            t.startsWith("- ") || t.startsWith("* ") -> { flush(); blocks.add(MdBlock.Bullet(t.drop(2))) }
            NUMBERED.matches(t) -> {
                flush()
                val m = NUMBERED.find(t)!!
                blocks.add(MdBlock.Numbered(m.groupValues[1].toInt(), m.groupValues[2]))
            }
            t.isBlank() -> flush()
            else -> {
                if (para.isNotEmpty()) para.append(" ")
                para.append(line.trim())
            }
        }
        i++
    }
    flush()
    return blocks
}

private fun inline(text: String, codeBg: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else { append(text[i]); i++ }
            }
            (text[i] == '*' || text[i] == '_') -> {
                val end = text.indexOf(text[i], i + 1)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end != -1) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            else -> { append(text[i]); i++ }
        }
    }
}
