package com.aliceincradle.launcher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aliceincradle.launcher.data.FileItem
import com.aliceincradle.launcher.data.FileManagerDialog
import com.aliceincradle.launcher.data.FolderEntryType
import com.aliceincradle.launcher.data.FolderPageState
import com.aliceincradle.launcher.data.PosixPermissions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(
    state: FolderPageState,
    onToggleSelection: (String) -> Unit,
    onToggleSelectAll: () -> Unit,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onCopyClick: () -> Unit,
    onInfoClick: () -> Unit,
    onExportClick: () -> Unit,
    onRenameDraftChange: (String) -> Unit,
    onConfirmRename: () -> Unit,
    onConfirmDelete: () -> Unit,
    onOpenPermissionEdit: () -> Unit,
    onUpdatePermissions: ((PosixPermissions) -> PosixPermissions) -> Unit,
    onOctalInputChange: (String) -> Unit,
    onRecursiveToggle: (Boolean) -> Unit,
    onConfirmPermissionEdit: () -> Unit,
    onExportToDownload: () -> Unit,
    onCustomExportToUri: (String) -> Unit,
    onCancelFileOp: () -> Unit,
    onDismissDialog: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val safTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            onCustomExportToUri(uri.toString())
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DualPanePathHeader(
            currentPath = state.currentPath,
            targetPath = state.targetPath
        )

        state.actionNotice?.let { notice ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("folder_action_notice"),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
            ) {
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "文件列表（左侧图标与名称）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "选择",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onToggleSelectAll)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                            .testTag("folder_select_all_header")
                    )
                }
                HorizontalDivider()

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(end = 12.dp)
                            .testTag("folder_lazy_column"),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(items = state.items, key = { it.id }) { item ->
                            FileComparisonRow(
                                item = item,
                                onToggleSelection = { onToggleSelection(item.id) }
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        }
                    }

                    VerticalScrollbar(
                        listState = listState,
                        thumbTag = "folder_scrollbar_thumb",
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(8.dp)
                            .testTag("folder_vertical_scrollbar")
                    )
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = state.selectionSummaryText,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("folder_selection_summary"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = state.totalCountSummaryText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("folder_total_summary"),
                    maxLines = 1
                )
            }
        }

        FileManagerBottomActionBar(
            enabled = state.hasSelection,
            onRenameClick = onRenameClick,
            onDeleteClick = onDeleteClick,
            onCopyClick = onCopyClick,
            onInfoClick = onInfoClick,
            onExportClick = onExportClick
        )
    }

    when (val dialog = state.activeDialog) {
        null -> Unit
        is FileManagerDialog.FileOpProgress -> FileOpProgressDialog(
            dialog = dialog,
            onCancel = onCancelFileOp
        )
        is FileManagerDialog.DeleteConfirm -> DeleteConfirmDialog(
            dialog = dialog,
            onConfirm = onConfirmDelete,
            onDismiss = onDismissDialog
        )
        is FileManagerDialog.Properties -> PropertiesDialog(
            dialog = dialog,
            onEditPermissions = onOpenPermissionEdit,
            onDismiss = onDismissDialog
        )
        is FileManagerDialog.PermissionEdit -> PermissionEditDialog(
            dialog = dialog,
            onUpdatePermissions = onUpdatePermissions,
            onOctalInputChange = onOctalInputChange,
            onRecursiveToggle = onRecursiveToggle,
            onConfirm = onConfirmPermissionEdit,
            onDismiss = onDismissDialog
        )
        is FileManagerDialog.Rename -> RenameFileDialog(
            dialog = dialog,
            onDraftChange = onRenameDraftChange,
            onConfirm = onConfirmRename,
            onDismiss = onDismissDialog
        )
        is FileManagerDialog.ExportSheet -> ExportOptionsSheet(
            dialog = dialog,
            onExportToDownload = onExportToDownload,
            onLaunchCustomFolderPicker = { safTreeLauncher.launch(null) },
            onDismiss = onDismissDialog
        )
    }
}

@Composable
private fun DualPanePathHeader(
    currentPath: String,
    targetPath: String
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "当前路径",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = currentPath,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("folder_current_path")
                )
            }
            VerticalDivider(
                modifier = Modifier
                    .height(28.dp)
                    .padding(horizontal = 10.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = "目标路径",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = targetPath,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("folder_target_path")
                )
            }
        }
    }
}

@Composable
private fun FileComparisonRow(
    item: FileItem,
    onToggleSelection: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .clickable(onClick = onToggleSelection)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("folder_item_${item.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (item.type == FolderEntryType.EXECUTABLE) {
                PlaceholderLogoBox(
                    text = item.logoText,
                    size = 36.dp,
                    modifier = Modifier.testTag("folder_exe_logo")
                )
            } else {
                val (icon, tint, desc) = resolveEntryIcon(item)
                Icon(
                    imageVector = icon,
                    contentDescription = desc,
                    tint = tint,
                    modifier = Modifier
                        .size(28.dp)
                        .testTag("folder_item_icon_${item.id}")
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${item.sizeLabel} · ${item.permissions}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        VerticalDivider(
            modifier = Modifier
                .height(34.dp)
                .padding(horizontal = 8.dp)
        )

        Checkbox(
            checked = item.isSelected,
            onCheckedChange = { onToggleSelection() },
            modifier = Modifier.testTag("folder_checkbox_${item.id}")
        )
    }
}

@Composable
private fun FileManagerBottomActionBar(
    enabled: Boolean,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onCopyClick: () -> Unit,
    onInfoClick: () -> Unit,
    onExportClick: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .testTag("file_bottom_action_bar"),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ActionIconButton(
                icon = Icons.Default.Edit,
                contentDescription = "重命名",
                enabled = enabled,
                testTag = "file_action_rename",
                onClick = onRenameClick
            )
            ActionIconButton(
                icon = Icons.Default.Delete,
                contentDescription = "删除",
                enabled = enabled,
                testTag = "file_action_delete",
                onClick = onDeleteClick
            )
            ActionIconButton(
                icon = Icons.Default.ContentCopy,
                contentDescription = "复制",
                enabled = enabled,
                testTag = "file_action_copy",
                onClick = onCopyClick
            )
            ActionIconButton(
                icon = Icons.Default.Info,
                contentDescription = "信息",
                enabled = enabled,
                testTag = "file_action_info",
                onClick = onInfoClick
            )
            ActionIconButton(
                icon = Icons.Default.Share,
                contentDescription = "导出",
                enabled = enabled,
                testTag = "file_action_export",
                onClick = onExportClick
            )
        }
    }
}

@Composable
private fun ActionIconButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.testTag(testTag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription
        )
    }
}

@Composable
private fun FileOpProgressDialog(
    dialog: FileManagerDialog.FileOpProgress,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = "${dialog.operationTitle} ${dialog.progressPercent}%",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = dialog.currentFileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "目标：${dialog.targetDescription}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                LinearProgressIndicator(
                    progress = { (dialog.progressPercent / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dialog_progress_bar")
                )
            }
        },
        confirmButton = {
            IconButton(
                onClick = onCancel,
                modifier = Modifier.testTag("dialog_progress_cancel")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "取消"
                )
            }
        }
    )
}

@Composable
private fun DeleteConfirmDialog(
    dialog: FileManagerDialog.DeleteConfirm,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "危险警告",
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(
                text = "确定删除？一旦确定不可恢复",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "待删除项目（共 ${dialog.targets.size} 项）：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                dialog.targets.take(5).forEach { target ->
                    Text(
                        text = "• ${target.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        confirmButton = {
            FilledIconButton(
                onClick = onConfirm,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                modifier = Modifier.testTag("dialog_delete_confirm")
            ) {
                Icon(imageVector = Icons.Default.Check, contentDescription = "确认删除")
            }
        },
        dismissButton = {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("dialog_delete_cancel")
            ) {
                Icon(imageVector = Icons.Default.Close, contentDescription = "取消")
            }
        }
    )
}

@Composable
private fun PropertiesDialog(
    dialog: FileManagerDialog.Properties,
    onEditPermissions: () -> Unit,
    onDismiss: () -> Unit
) {
    val item = dialog.item
    val symbolic = PosixPermissions.fromOctal(item.permissions)?.toSymbolicString() ?: "---------"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PlaceholderLogoBox(
                    text = item.logoText,
                    size = 44.dp,
                    modifier = Modifier.testTag("dialog_properties_logo")
                )
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PropertyRow(label = "类型", value = if (item.isFolder) "文件夹" else "文件")
                PropertyRow(label = "大小", value = item.sizeLabel)
                PropertyRow(label = "路径", value = dialog.resolvedPath)
                PropertyRow(label = "权限", value = "$symbolic (${item.permissions})")
                if (item.recursiveApplied) {
                    PropertyRow(label = "递归状态", value = "已启用子目录递归")
                }
            }
        },
        confirmButton = {
            FilledTonalIconButton(
                onClick = onEditPermissions,
                modifier = Modifier.testTag("dialog_properties_edit_perm")
            ) {
                Icon(imageVector = Icons.Default.Security, contentDescription = "修改权限")
            }
        },
        dismissButton = {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("dialog_properties_close")
            ) {
                Icon(imageVector = Icons.Default.Close, contentDescription = "关闭")
            }
        }
    )
}

@Composable
private fun PropertyRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun PermissionEditDialog(
    dialog: FileManagerDialog.PermissionEdit,
    onUpdatePermissions: ((PosixPermissions) -> PosixPermissions) -> Unit,
    onOctalInputChange: (String) -> Unit,
    onRecursiveToggle: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val perm = dialog.permissions

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = "权限修改 · ${dialog.item.name}",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "特殊权限",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    CompactCheckboxItem(
                        label = "SUID",
                        checked = perm.suid,
                        tag = "perm_cb_suid",
                        onCheckedChange = { checked -> onUpdatePermissions { it.copy(suid = checked) } }
                    )
                    CompactCheckboxItem(
                        label = "SGID",
                        checked = perm.sgid,
                        tag = "perm_cb_sgid",
                        onCheckedChange = { checked -> onUpdatePermissions { it.copy(sgid = checked) } }
                    )
                    CompactCheckboxItem(
                        label = "Sticky",
                        checked = perm.stickyBit,
                        tag = "perm_cb_sticky",
                        onCheckedChange = { checked -> onUpdatePermissions { it.copy(stickyBit = checked) } }
                    )
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "角色",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f)
                    )
                    listOf("R", "W", "X").forEach { col ->
                        Text(
                            text = col,
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(46.dp)
                        )
                    }
                }

                PermissionMatrixRow(
                    roleLabel = "所有者",
                    r = perm.ownerR,
                    w = perm.ownerW,
                    x = perm.ownerX,
                    tagPrefix = "perm_cb_owner",
                    onToggleR = { v -> onUpdatePermissions { it.copy(ownerR = v) } },
                    onToggleW = { v -> onUpdatePermissions { it.copy(ownerW = v) } },
                    onToggleX = { v -> onUpdatePermissions { it.copy(ownerX = v) } }
                )
                PermissionMatrixRow(
                    roleLabel = "用户组",
                    r = perm.groupR,
                    w = perm.groupW,
                    x = perm.groupX,
                    tagPrefix = "perm_cb_group",
                    onToggleR = { v -> onUpdatePermissions { it.copy(groupR = v) } },
                    onToggleW = { v -> onUpdatePermissions { it.copy(groupW = v) } },
                    onToggleX = { v -> onUpdatePermissions { it.copy(groupX = v) } }
                )
                PermissionMatrixRow(
                    roleLabel = "其他",
                    r = perm.otherR,
                    w = perm.otherW,
                    x = perm.otherX,
                    tagPrefix = "perm_cb_other",
                    onToggleR = { v -> onUpdatePermissions { it.copy(otherR = v) } },
                    onToggleW = { v -> onUpdatePermissions { it.copy(otherW = v) } },
                    onToggleX = { v -> onUpdatePermissions { it.copy(otherX = v) } }
                )

                OutlinedTextField(
                    value = dialog.octalInput,
                    onValueChange = onOctalInputChange,
                    label = { Text(text = "八进制权限码（如 0755 / 4755）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    leadingIcon = {
                        Icon(imageVector = Icons.Default.Lock, contentDescription = null)
                    },
                    trailingIcon = {
                        IconButton(
                            onClick = { onOctalInputChange("") },
                            modifier = Modifier.testTag("perm_octal_clear")
                        ) {
                            Icon(imageVector = Icons.Default.Clear, contentDescription = "清除")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("perm_octal_input")
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onRecursiveToggle(!dialog.applyRecursively) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = dialog.applyRecursively,
                        onCheckedChange = onRecursiveToggle,
                        modifier = Modifier.testTag("perm_cb_recursive")
                    )
                    Text(
                        text = "同时应用到子文件或子文件夹（递归）",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            FilledIconButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("dialog_perm_confirm")
            ) {
                Icon(imageVector = Icons.Default.Check, contentDescription = "应用权限")
            }
        },
        dismissButton = {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("dialog_perm_cancel")
            ) {
                Icon(imageVector = Icons.Default.Close, contentDescription = "取消")
            }
        }
    )
}

@Composable
private fun CompactCheckboxItem(
    label: String,
    checked: Boolean,
    tag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(tag)
        )
        Text(text = label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PermissionMatrixRow(
    roleLabel: String,
    r: Boolean,
    w: Boolean,
    x: Boolean,
    tagPrefix: String,
    onToggleR: (Boolean) -> Unit,
    onToggleW: (Boolean) -> Unit,
    onToggleX: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = roleLabel,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        Box(modifier = Modifier.width(46.dp), contentAlignment = Alignment.Center) {
            Checkbox(checked = r, onCheckedChange = onToggleR, modifier = Modifier.testTag("${tagPrefix}_r"))
        }
        Box(modifier = Modifier.width(46.dp), contentAlignment = Alignment.Center) {
            Checkbox(checked = w, onCheckedChange = onToggleW, modifier = Modifier.testTag("${tagPrefix}_w"))
        }
        Box(modifier = Modifier.width(46.dp), contentAlignment = Alignment.Center) {
            Checkbox(checked = x, onCheckedChange = onToggleX, modifier = Modifier.testTag("${tagPrefix}_x"))
        }
    }
}

@Composable
private fun RenameFileDialog(
    dialog: FileManagerDialog.Rename,
    onDraftChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(text = "重命名", style = MaterialTheme.typography.titleMedium)
        },
        text = {
            OutlinedTextField(
                value = dialog.draftName,
                onValueChange = onDraftChange,
                singleLine = true,
                label = { Text(text = "新名称") },
                trailingIcon = {
                    IconButton(
                        onClick = { onDraftChange("") },
                        modifier = Modifier.testTag("dialog_rename_clear")
                    ) {
                        Icon(imageVector = Icons.Default.Clear, contentDescription = "清除")
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("dialog_rename_input")
            )
        },
        confirmButton = {
            FilledIconButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("dialog_rename_confirm")
            ) {
                Icon(imageVector = Icons.Default.Check, contentDescription = "确定")
            }
        },
        dismissButton = {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("dialog_rename_cancel")
            ) {
                Icon(imageVector = Icons.Default.Close, contentDescription = "取消")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportOptionsSheet(
    dialog: FileManagerDialog.ExportSheet,
    onExportToDownload: () -> Unit,
    onLaunchCustomFolderPicker: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("dialog_export_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "导出所选文件（${dialog.targets.size} 项）",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Text(
                text = "当前 Download 目录：",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            dialog.downloadTargets.forEach { target ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = Color(0xFFEF9A00),
                            modifier = Modifier.size(20.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = target.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = target.displayPath,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            FilledTonalButton(
                onClick = onExportToDownload,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("export_to_download_btn")
            ) {
                Icon(imageVector = Icons.Default.Download, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "复制至 Download")
            }

            OutlinedButton(
                onClick = onLaunchCustomFolderPicker,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("export_custom_saf_btn"),
                colors = ButtonDefaults.outlinedButtonColors()
            ) {
                Icon(imageVector = Icons.Default.FolderOpen, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "自定义导出至文件夹下")
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

private fun resolveEntryIcon(item: FileItem): Triple<ImageVector, Color, String> {
    return when (item.type) {
        FolderEntryType.FOLDER -> Triple(Icons.Default.Folder, Color(0xFFEF9A00), "文件夹")
        FolderEntryType.TEXT -> Triple(Icons.Default.Description, Color(0xFF546E7A), "文本文件")
        FolderEntryType.PYTHON -> Triple(Icons.Default.Code, Color(0xFF1565C0), "Python 脚本")
        FolderEntryType.SHELL -> Triple(Icons.Default.Terminal, Color(0xFF2E7D32), "Shell 脚本")
        FolderEntryType.EXECUTABLE, FolderEntryType.OTHER -> Triple(
            Icons.AutoMirrored.Filled.InsertDriveFile,
            Color(0xFF616161),
            "文件"
        )
    }
}
