package com.aliceincradle.launcher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aliceincradle.launcher.data.DownloadCatalogItem
import com.aliceincradle.launcher.data.DownloadContentStatus
import com.aliceincradle.launcher.data.DownloadFilterState
import com.aliceincradle.launcher.data.DownloadPageState

@Composable
fun DownloadScreen(
    state: DownloadPageState,
    onQueryChange: (String) -> Unit,
    onAppSelect: (String) -> Unit,
    onVersionSelect: (String) -> Unit,
    onCategorySelect: (String) -> Unit,
    onIncludeCompatibleChange: (Boolean) -> Unit,
    onIncludeFeaturedChange: (Boolean) -> Unit,
    onDownloadClick: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onOpenDownloadedFile: (DownloadCatalogItem) -> Unit,
    onOpenCienFallback: () -> Unit,
    onRefreshCatalog: () -> Unit,
    onDetailClick: (DownloadCatalogItem) -> Unit,
    onDismissDetail: () -> Unit,
    onOpenManualShortCode: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        DownloadFilterSection(
            filter = state.filter,
            onQueryChange = onQueryChange,
            onAppSelect = onAppSelect,
            onVersionSelect = onVersionSelect,
            onCategorySelect = onCategorySelect,
            onIncludeCompatibleChange = onIncludeCompatibleChange,
            onIncludeFeaturedChange = onIncludeFeaturedChange
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = state.statsText,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.catalogError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .testTag("download_stats_text")
            )
            IconButton(
                onClick = onRefreshCatalog,
                enabled = !state.isLoadingCatalog,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "刷新列表",
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        state.catalogError?.let { errorMsg ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("download_mirror_error_card"),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = errorMsg,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onRefreshCatalog,
                            modifier = Modifier.testTag("download_retry_catalog_button")
                        ) {
                            Text(text = "重试")
                        }
                        OutlinedButton(
                            onClick = onOpenCienFallback,
                            modifier = Modifier.testTag("download_cien_fallback_button")
                        ) {
                            Text(text = "前往官方 ci-en 发布页")
                        }
                        OutlinedButton(
                            onClick = onOpenManualShortCode,
                            modifier = Modifier.testTag("download_manual_shortcode_button")
                        ) {
                            Text(text = "手动填入短码")
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (state.items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无匹配内容",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = 14.dp)
                        .testTag("download_lazy_column"),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 84.dp)
                ) {
                    items(items = state.items, key = { it.id }) { item ->
                        DownloadCatalogRowCard(
                            item = item,
                            onDownloadClick = onDownloadClick,
                            onCancelDownload = onCancelDownload,
                            onOpenDownloadedFile = onOpenDownloadedFile,
                            onDetailClick = onDetailClick
                        )
                    }
                }
            }

            VerticalScrollbar(
                listState = listState,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(8.dp)
                    .testTag("download_vertical_scrollbar")
            )
        }
    }

    state.selectedDetailItem?.let { detailItem ->
        DownloadCatalogDetailDialog(
            item = detailItem,
            onDismiss = onDismissDetail
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadFilterSection(
    filter: DownloadFilterState,
    onQueryChange: (String) -> Unit,
    onAppSelect: (String) -> Unit,
    onVersionSelect: (String) -> Unit,
    onCategorySelect: (String) -> Unit,
    onIncludeCompatibleChange: (Boolean) -> Unit,
    onIncludeFeaturedChange: (Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = filter.query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("download_search_field"),
            shape = RoundedCornerShape(24.dp),
            placeholder = {
                Text(
                    text = "按名称 / ID / 作者等搜索...",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "搜索"
                )
            },
            singleLine = true
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppDropdownButton(
                selectedValue = filter.selectedApp,
                options = filter.appOptions,
                onOptionSelected = onAppSelect,
                modifier = Modifier
                    .weight(1.05f)
                    .testTag("dropdown_app"),
                leadingContent = {
                    Surface(
                        modifier = Modifier
                            .size(24.dp)
                            .testTag("download_app_logo"),
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Apps,
                                contentDescription = "应用图标",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            )

            AppDropdownButton(
                selectedValue = filter.selectedVersion,
                options = filter.versionOptions,
                onOptionSelected = onVersionSelect,
                modifier = Modifier
                    .weight(0.9f)
                    .testTag("dropdown_version")
            )

            AppDropdownButton(
                selectedValue = filter.selectedCategory,
                options = filter.categoryOptions,
                onOptionSelected = onCategorySelect,
                modifier = Modifier
                    .weight(1.05f)
                    .testTag("dropdown_category")
            )
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            FilterCheckboxItem(
                label = "包含已兼容版本的 Mod",
                checked = filter.includeCompatibleMods,
                onCheckedChange = onIncludeCompatibleChange
            )
            FilterCheckboxItem(
                label = "包含精品 Mod",
                checked = filter.includeFeaturedMods,
                onCheckedChange = onIncludeFeaturedChange
            )
        }
    }
}

@Composable
private fun FilterCheckboxItem(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1
        )
    }
}

@Composable
private fun DownloadCatalogRowCard(
    item: DownloadCatalogItem,
    onDownloadClick: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onOpenDownloadedFile: (DownloadCatalogItem) -> Unit,
    onDetailClick: (DownloadCatalogItem) -> Unit
) {
    val (statusIcon, statusTint, statusDesc) = when (item.status) {
        DownloadContentStatus.DOWNLOADED -> Triple(Icons.Default.Check, Color(0xFF2E7D32), "已下载")
        DownloadContentStatus.AVAILABLE -> Triple(Icons.Default.Download, Color(0xFF1565C0), "可下载")
        DownloadContentStatus.QUEUED,
        DownloadContentStatus.DOWNLOADING -> Triple(Icons.Default.Download, Color(0xFFEF6C00), "下载中")
        DownloadContentStatus.FAILED -> Triple(Icons.Default.ErrorOutline, Color(0xFFC62828), "下载失败")
        DownloadContentStatus.DISABLED -> Triple(Icons.Default.Block, Color(0xFF757575), "已禁用或不兼容")
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = statusIcon,
                contentDescription = statusDesc,
                tint = statusTint,
                modifier = Modifier
                    .size(22.dp)
                    .testTag("status_icon_${item.id}")
            )

            PlaceholderLogoBox(text = item.logoText, size = 48.dp)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.tags.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.status == DownloadContentStatus.DOWNLOADING || item.status == DownloadContentStatus.QUEUED) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LinearProgressIndicator(
                            progress = { (item.progressPercent / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { onCancelDownload(item.id) },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier
                                .heightIn(min = 28.dp)
                                .testTag("download_cancel_${item.id}")
                        ) {
                            Text(
                                text = "取消",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                if (item.status == DownloadContentStatus.FAILED && !item.errorMessage.isNullOrBlank()) {
                    Text(
                        text = "失败：${item.errorMessage}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val canOpenDownloaded = item.status == DownloadContentStatus.DOWNLOADED && !item.localFilePath.isNullOrBlank()
                    val actionEnabled = item.status == DownloadContentStatus.AVAILABLE ||
                        item.status == DownloadContentStatus.FAILED ||
                        canOpenDownloaded
                    val actionLabel = when (item.status) {
                        DownloadContentStatus.DOWNLOADED -> if (canOpenDownloaded) "安装/查看" else "已下载"
                        DownloadContentStatus.QUEUED -> "排队中"
                        DownloadContentStatus.DOWNLOADING -> "下载中 ${item.progressPercent}%"
                        DownloadContentStatus.FAILED -> "重试"
                        DownloadContentStatus.DISABLED -> "不可用"
                        DownloadContentStatus.AVAILABLE -> "下载"
                    }
                    OutlinedButton(
                        onClick = {
                            if (canOpenDownloaded) {
                                onOpenDownloadedFile(item)
                            } else {
                                onDownloadClick(item.id)
                            }
                        },
                        enabled = actionEnabled,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                        modifier = Modifier.heightIn(min = 32.dp)
                    ) {
                        Text(
                            text = actionLabel,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(
                        onClick = { onDetailClick(item) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.heightIn(min = 32.dp)
                    ) {
                        Text(
                            text = "查看详情",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = item.sizeLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadCatalogDetailDialog(
    item: DownloadCatalogItem,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "ID：${item.id}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "作者：${item.author} · 大小：${item.sizeLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "标签：${item.tags.joinToString(" · ")}",
                    style = MaterialTheme.typography.bodySmall
                )
                if (item.description.isNotBlank()) {
                    Text(
                        text = item.description,
                        style = MaterialTheme.typography.bodyMedium
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
