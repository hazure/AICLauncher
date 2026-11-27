package com.aliceincradle.launcher.ui.file

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aliceincradle.launcher.ui.icon.FileIconResolver
import com.aliceincradle.launcher.ui.icon.FileIcons
import com.aliceincradle.launcher.ui.icon.IconSpec
import java.io.File

/**
 * Dedicated file/directory picker dialog separated from file-browser click-to-open semantics.
 * Supports explicit selection + confirm (returns File) or cancel (returns nothing).
 */
@Composable
fun FilePickerDialog(
    initialDir: File,
    pickDirectoryOnly: Boolean = true,
    onConfirm: (File) -> Unit,
    onDismiss: () -> Unit
) {
    var currentDir by remember { mutableStateOf(initialDir) }
    var selectedFile by remember { mutableStateOf<File?>(if (pickDirectoryOnly) initialDir else null) }

    val entries = remember(currentDir) {
        val children = currentDir.listFiles()?.filter { !it.name.startsWith(".") }
            ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            ?: emptyList()
        children
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = if (pickDirectoryOnly) "选择游戏目录" else "选择文件") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "当前：${currentDir.absolutePath}",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (currentDir.parentFile != null && currentDir.parentFile?.canRead() == true) {
                        item(key = "__picker_parent__") {
                            PickerRow(
                                name = "..",
                                iconSpec = IconSpec.PARENT,
                                isSelected = false,
                                onClick = {
                                    currentDir.parentFile?.let { parent ->
                                        currentDir = parent
                                        if (pickDirectoryOnly) selectedFile = parent
                                    }
                                }
                            )
                        }
                    }
                    items(items = entries, key = { it.absolutePath }) { file ->
                        val spec = FileIconResolver.resolveFile(file)
                        val isSelected = selectedFile?.absolutePath == file.absolutePath
                        PickerRow(
                            name = file.name,
                            iconSpec = spec,
                            isSelected = isSelected,
                            onClick = {
                                if (file.isDirectory) {
                                    currentDir = file
                                    if (pickDirectoryOnly) selectedFile = file
                                } else if (!pickDirectoryOnly) {
                                    selectedFile = if (isSelected) null else file
                                }
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { selectedFile?.let(onConfirm) },
                enabled = selectedFile != null
            ) {
                Text("确认")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun PickerRow(
    name: String,
    iconSpec: IconSpec,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = FileIcons.iconFor(iconSpec),
                contentDescription = FileIcons.contentDescriptionFor(iconSpec),
                tint = FileIcons.tintFor(iconSpec),
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
