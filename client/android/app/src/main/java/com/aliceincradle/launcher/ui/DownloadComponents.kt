package com.aliceincradle.launcher.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.aliceincradle.launcher.data.DownloadCatalogItem
import com.aliceincradle.launcher.data.DownloadContentStatus
import com.aliceincradle.launcher.data.DownloadFilterState
import com.aliceincradle.launcher.data.DownloadPageState
import kotlin.math.roundToInt

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
    onDetailClick: (DownloadCatalogItem) -> Unit,
    onDismissDetail: () -> Unit,
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

        Text(
            text = state.statsText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("download_stats_text")
        )

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
            FilterDropdownButton(
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

            FilterDropdownButton(
                selectedValue = filter.selectedVersion,
                options = filter.versionOptions,
                onOptionSelected = onVersionSelect,
                modifier = Modifier
                    .weight(0.9f)
                    .testTag("dropdown_version")
            )

            FilterDropdownButton(
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
private fun FilterDropdownButton(
    selectedValue: String,
    options: List<String>,
    onOptionSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    leadingContent: (@Composable () -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable { expanded = true },
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            color = MaterialTheme.colorScheme.surface
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (leadingContent != null) {
                    leadingContent()
                }
                Text(
                    text = selectedValue,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "展开选项",
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(text = option) },
                    onClick = {
                        expanded = false
                        onOptionSelected(option)
                    }
                )
            }
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
    onDetailClick: (DownloadCatalogItem) -> Unit
) {
    val (statusIcon, statusTint, statusDesc) = when (item.status) {
        DownloadContentStatus.DOWNLOADED -> Triple(Icons.Default.Check, Color(0xFF2E7D32), "已下载")
        DownloadContentStatus.DOWNLOADING -> Triple(Icons.Default.Download, Color(0xFF1565C0), "下载中或可下载")
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

            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = item.logoText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1
                    )
                }
            }

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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { onDownloadClick(item.id) },
                        enabled = item.status != DownloadContentStatus.DISABLED,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                        modifier = Modifier.heightIn(min = 32.dp)
                    ) {
                        Text(
                            text = if (item.status == DownloadContentStatus.DOWNLOADED) "已下载" else "下载",
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
fun VerticalScrollbar(
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    val layoutInfo by remember { derivedStateOf { listState.layoutInfo } }
    val canScroll by remember {
        derivedStateOf { listState.canScrollForward || listState.canScrollBackward }
    }

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter
    ) {
        val density = LocalDensity.current
        val trackHeightPx = with(density) { maxHeight.toPx() }
        val minThumbPx = with(density) { 28.dp.toPx() }
        val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        val thumbColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.78f)

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(trackColor)
        )

        val visibleItems = layoutInfo.visibleItemsInfo
        val totalItems = layoutInfo.totalItemsCount
        if (canScroll && totalItems > 0 && visibleItems.isNotEmpty() && trackHeightPx > 0f) {
            val viewportHeight = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset)
                .toFloat()
                .coerceAtLeast(1f)
            val avgItemSize = (visibleItems.sumOf { it.size }.toFloat() / visibleItems.size)
                .coerceAtLeast(1f)
            val estimatedTotalSize = (avgItemSize * totalItems).coerceAtLeast(viewportHeight)
            val thumbHeightPx = (trackHeightPx * (viewportHeight / estimatedTotalSize))
                .coerceIn(minThumbPx.coerceAtMost(trackHeightPx), trackHeightPx)
            val scrollOffsetPx =
                listState.firstVisibleItemIndex * avgItemSize + listState.firstVisibleItemScrollOffset
            val maxScrollPx = (estimatedTotalSize - viewportHeight).coerceAtLeast(1f)
            val scrollFraction = (scrollOffsetPx / maxScrollPx).coerceIn(0f, 1f)
            val thumbOffsetPx = (trackHeightPx - thumbHeightPx) * scrollFraction
            val thumbHeightDp = with(density) { thumbHeightPx.toDp() }

            Box(
                modifier = Modifier
                    .offset { IntOffset(0, thumbOffsetPx.roundToInt()) }
                    .width(5.dp)
                    .height(thumbHeightDp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(thumbColor)
                    .testTag("download_scrollbar_thumb")
            )
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
