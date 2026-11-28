package com.aliceincradle.launcher.ui.file

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliceincradle.launcher.data.FileManagerDialog

private val URL_REGEX = Regex("""https?://[^\s)\]>]+""")
private val CODE_KEYWORD_REGEX = Regex("""\b(fun|val|var|class|object|if|else|for|while|return|import|package|def|public|private|internal|true|false|null)\b""")
private val NUMBER_REGEX = Regex("""\b\d+(\.\d+)?\b""")

@Composable
fun ReadOnlyViewerDialog(
    dialog: FileManagerDialog.ReadOnlyViewer,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = dialog.item.name) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (dialog.isTruncated) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = "文件较大，已截断仅展示前部分内容（只读）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                if (dialog.isMarkdown) {
                    MarkdownReadOnlyContent(
                        raw = dialog.content,
                        onLinkClick = { url ->
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            } catch (_: ActivityNotFoundException) {
                                Toast.makeText(context, "未找到可打开链接的应用", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                } else {
                    Text(
                        text = dialog.content,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "关闭")
            }
        }
    )
}

@Suppress("DEPRECATION")
@Composable
private fun MarkdownReadOnlyContent(
    raw: String,
    onLinkClick: (String) -> Unit
) {
    val lines = raw.lines()
    var inCodeBlock = false
    lines.forEach { line ->
        val trimmed = line.trim()
        if (trimmed.startsWith("```")) {
            inCodeBlock = !inCodeBlock
            return@forEach
        }
        if (inCodeBlock) {
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.tertiary
            )
            return@forEach
        }
        when {
            trimmed.startsWith("# ") -> Text(
                text = trimmed.removePrefix("# "),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            trimmed.startsWith("## ") -> Text(
                text = trimmed.removePrefix("## "),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> Text(
                text = "• ${trimmed.drop(2)}",
                style = MaterialTheme.typography.bodySmall
            )
            else -> {
                val annotated = buildLinkAnnotatedString(line)
                ClickableText(
                    text = annotated,
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                    onClick = { offset ->
                        annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let {
                            onLinkClick(it.item)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun buildLinkAnnotatedString(text: String): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        var lastIdx = 0
        URL_REGEX.findAll(text).forEach { match ->
            append(text.substring(lastIdx, match.range.first))
            pushStringAnnotation(tag = "URL", annotation = match.value)
            withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                append(match.value)
            }
            pop()
            lastIdx = match.range.last + 1
        }
        if (lastIdx < text.length) {
            append(text.substring(lastIdx))
        }
    }
}

@Composable
fun CodeEditorDialog(
    dialog: FileManagerDialog.CodeEditor,
    onContentChange: (String) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onInsertTab: () -> Unit,
    onSave: () -> Unit,
    onRequestClose: () -> Unit,
    onConfirmDiscard: () -> Unit,
    onDismissDiscardConfirm: () -> Unit
) {
    val lines = dialog.content.lines()
    val lineCount = lines.size.coerceAtLeast(1)
    val bracketBalanced = checkBracketsBalanced(dialog.content)

    AlertDialog(
        onDismissRequest = onRequestClose,
        title = {
            Text(text = "${dialog.item.name}${if (dialog.isDirty) " *" else ""}")
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(onClick = onUndo, enabled = dialog.undoStack.isNotEmpty()) {
                        Text("撤销", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = onRedo, enabled = dialog.redoStack.isNotEmpty()) {
                        Text("重做", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = onInsertTab) {
                        Text("Tab(4)", fontSize = 11.sp)
                    }
                }
                Text(
                    text = "共 $lineCount 行 · 括号${if (bracketBalanced) "匹配" else "未闭合"} · ${if (dialog.lineEnding == "\r\n") "CRLF" else "LF"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (bracketBalanced) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                ) {
                    Column(modifier = Modifier.padding(end = 8.dp)) {
                        for (i in 1..lineCount) {
                            Text(
                                text = i.toString(),
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            )
                        }
                    }
                    OutlinedTextField(
                        value = dialog.content,
                        onValueChange = onContentChange,
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        modifier = Modifier.width(320.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onSave) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onRequestClose) {
                Text("关闭")
            }
        }
    )

    if (dialog.showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = onDismissDiscardConfirm,
            title = { Text("未保存的修改") },
            text = { Text("当前文件有未保存修改，是否保存后离开？") },
            confirmButton = {
                Button(onClick = onSave) { Text("保存") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onConfirmDiscard) { Text("丢弃") }
                    TextButton(onClick = onDismissDiscardConfirm) { Text("取消") }
                }
            }
        )
    }
}

private fun checkBracketsBalanced(text: String): Boolean {
    var round = 0
    var curly = 0
    var square = 0
    for (ch in text) {
        when (ch) {
            '(' -> round++
            ')' -> round--
            '{' -> curly++
            '}' -> curly--
            '[' -> square++
            ']' -> square--
        }
        if (round < 0 || curly < 0 || square < 0) return false
    }
    return round == 0 && curly == 0 && square == 0
}

@Composable
fun ConfigTemplateDialog(
    dialog: FileManagerDialog.ConfigTemplateForm,
    onFieldChange: (Int, String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = dialog.templateName) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                dialog.fields.forEachIndexed { idx, field ->
                    Text(
                        text = "[${field.section}] ${field.key}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (field.comment.isNotBlank()) {
                        Text(
                            text = field.comment,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedTextField(
                        value = field.value,
                        onValueChange = { onFieldChange(idx, it) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onSave) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
fun ConfigUnmatchedDialog(
    dialog: FileManagerDialog.ConfigUnmatchedPrompt,
    onDirectEdit: () -> Unit,
    onSearchOnline: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "未匹配内置配置模板") },
        text = { Text(text = "文件 ${dialog.item.name} 暂无内置配置模板，请选择操作方式：") },
        confirmButton = {
            Button(onClick = onDirectEdit) {
                Text(if (dialog.fallbackToReadOnly) "只读/直接打开" else "直接编辑")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onSearchOnline) { Text("联网搜索") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}
