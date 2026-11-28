package com.aliceincradle.launcher.ui

import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aliceincradle.launcher.data.SettingsPageState

@Composable
fun SettingsSwitchItem(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun SettingsDropdownItem(
    title: String,
    selectedValue: String,
    options: List<String>,
    onOptionSelected: (String) -> Unit,
    helpText: String? = null,
    dropdownTag: String? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        AppDropdownButton(
            selectedValue = selectedValue,
            options = options,
            onOptionSelected = onOptionSelected,
            leadingContent = leadingContent,
            textStyle = MaterialTheme.typography.bodyMedium,
            minHeight = 44.dp,
            modifier = Modifier
                .fillMaxWidth()
                .let { if (dropdownTag != null) it.testTag(dropdownTag) else it }
        )
        if (!helpText.isNullOrBlank()) {
            Text(
                text = helpText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SettingsPathSelectItem(
    title: String,
    pathValue: String,
    placeholder: String,
    onSelectClick: () -> Unit,
    onClearClick: (() -> Unit)? = null,
    itemTag: String? = null,
    clearTag: String? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .let { if (itemTag != null) it.testTag(itemTag) else it }
            .clickable(onClick = onSelectClick)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = pathValue.ifBlank { placeholder },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (pathValue.isNotBlank() && onClearClick != null) {
            IconButton(
                onClick = onClearClick,
                modifier = Modifier.let { if (clearTag != null) it.testTag(clearTag) else it }
            ) {
                Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = "清除路径"
                )
            }
        }
    }
}

@Composable
fun SettingsScreen(
    state: SettingsPageState,
    onThemeSelect: (String) -> Unit,
    onShowHiddenFilesChange: (Boolean) -> Unit,
    onAutoCheckUpdateChange: (Boolean) -> Unit,
    onRootfsSelect: (String) -> Unit,
    onGraphicsRendererSelect: (String) -> Unit,
    onMountPathPicked: (String) -> Unit,
    onClearMountPath: () -> Unit,
    onCpuLimitSelect: (Int) -> Unit,
    onMemLimitSelect: (Int) -> Unit,
    onOpenResetContainerDialog: () -> Unit,
    onConfirmResetContainer: () -> Unit,
    onDismissResetContainerDialog: () -> Unit,
    onOpenEnvVarsDialog: () -> Unit,
    onEnvVarsDraftChange: (String) -> Unit,
    onConfirmEnvVars: () -> Unit,
    onDismissEnvVarsDialog: () -> Unit,
    onPythonVersionSelect: (String) -> Unit,
    onPythonWarningClick: () -> Unit,
    onPythonDirSelect: (String) -> Unit,
    onPythonDirPicked: (String) -> Unit,
    onOpenCustomPathDialog: () -> Unit,
    onCustomPathDraftChange: (String) -> Unit,
    onConfirmCustomPath: () -> Unit,
    onDismissCustomPathDialog: () -> Unit,
    onCleanTempFiles: () -> Unit,
    onExportLogs: () -> Unit,
    onOpenDisclaimerDialog: () -> Unit,
    onDismissDisclaimerDialog: () -> Unit,
    onOpenLicensesDialog: () -> Unit,
    onDismissLicensesDialog: () -> Unit,
    onVersionRowClick: () -> Unit,
    onLogLevelSelect: (String) -> Unit,
    onForceSoftwareRenderingChange: (Boolean) -> Unit,
    onReservedSectionClick: () -> Unit,
    onOpenManualShortCodeDialog: () -> Unit = {},
    onManualShortCodeDraftChange: (String) -> Unit = {},
    onManualFileNameDraftChange: (String) -> Unit = {},
    onManualVersionDraftChange: (String) -> Unit = {},
    onConfirmManualShortCode: () -> Unit = {},
    onDismissManualShortCodeDialog: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appVersionName = remember(context) {
        try {
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            pkgInfo.versionName?.takeIf { it.isNotBlank() } ?: "unknown"
        } catch (e: Exception) {
            Log.e("SettingsTab", "Failed to read package versionName", e)
            "unknown"
        }
    }
    val mountDirLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            onMountPathPicked(uri.toString())
        }
    }
    val pythonDirLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            onPythonDirPicked(uri.toString())
        }
    }

    val cpuLabels = state.cpuCoreOptions.map { if (it == 0) "不限制" else "$it 核心" }
    val selectedCpuLabel = if (state.cpuCoreLimit == 0) "不限制" else "${state.cpuCoreLimit} 核心"
    val memLabels = state.memLimitOptions.map { if (it == 0) "不限制" else "$it MB" }
    val selectedMemLabel = if (state.memLimitMB == 0) "不限制" else "${state.memLimitMB} MB"

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. 外观与网络
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_card_appearance")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "外观与网络",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                SettingsDropdownItem(
                    title = "主题模式",
                    selectedValue = state.themeMode,
                    options = state.themeOptions,
                    onOptionSelected = onThemeSelect,
                    dropdownTag = "settings_theme_dropdown"
                )
                SettingsSwitchItem(
                    title = "显示隐藏文件",
                    subtitle = "在文件管理器中展示以 '.' 开头的隐藏条目",
                    checked = state.showHiddenFiles,
                    onCheckedChange = onShowHiddenFilesChange,
                    modifier = Modifier.testTag("settings_hidden_files_switch")
                )
                SettingsSwitchItem(
                    title = "自动检查更新",
                    subtitle = "启动时检查 AICL 新版本发布",
                    checked = state.autoCheckUpdate,
                    onCheckedChange = onAutoCheckUpdateChange,
                    modifier = Modifier.testTag("settings_auto_update_switch")
                )
            }
        }

        // 2. 容器运行配置
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_card_container")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "容器运行配置",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(
                        onClick = onOpenResetContainerDialog,
                        modifier = Modifier.testTag("settings_reset_container_button")
                    ) {
                        Text(text = "重置本卡片")
                    }
                }
                SettingsDropdownItem(
                    title = "根文件系统 (RootFS)",
                    selectedValue = state.rootfsType,
                    options = state.rootfsOptions,
                    onOptionSelected = onRootfsSelect,
                    helpText = "选择容器运行所依赖的基础 Linux 发行版环境。",
                    dropdownTag = "settings_rootfs_dropdown"
                )
                SettingsDropdownItem(
                    title = "图形渲染后端",
                    selectedValue = state.graphicsRenderer,
                    options = state.graphicsRendererOptions,
                    onOptionSelected = onGraphicsRendererSelect,
                    helpText = "Turnip + DXVK 适合骁龙 Adreno GPU；LLVMpipe 为纯软件兜底。",
                    dropdownTag = "settings_graphics_dropdown"
                )
                SettingsPathSelectItem(
                    title = "挂载存储目录 (SAF)",
                    pathValue = state.containerMountPath,
                    placeholder = "未设置（点击选择文件夹）",
                    onSelectClick = { mountDirLauncher.launch(null) },
                    onClearClick = onClearMountPath,
                    itemTag = "settings_mount_path_item",
                    clearTag = "settings_mount_path_clear"
                )
                SettingsDropdownItem(
                    title = "CPU 核心数限制",
                    selectedValue = selectedCpuLabel,
                    options = cpuLabels,
                    onOptionSelected = { label ->
                        val parsed = if (label == "不限制") 0 else label.removeSuffix(" 核心").toIntOrNull() ?: 0
                        onCpuLimitSelect(parsed)
                    },
                    dropdownTag = "settings_cpu_limit_dropdown"
                )
                SettingsDropdownItem(
                    title = "内存上限",
                    selectedValue = selectedMemLabel,
                    options = memLabels,
                    onOptionSelected = { label ->
                        val parsed = if (label == "不限制") 0 else label.removeSuffix(" MB").toIntOrNull() ?: 0
                        onMemLimitSelect(parsed)
                    },
                    dropdownTag = "settings_mem_limit_dropdown"
                )
            }
        }

        // 3. 高级与环境配置
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_card_advanced")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "高级与环境配置",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("settings_python_card_title")
                )

                OutlinedButton(
                    onClick = onOpenEnvVarsDialog,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_env_vars_button")
                ) {
                    Text(
                        text = if (state.customEnvVars.isBlank()) {
                            "自定义环境变量（未配置）"
                        } else {
                            "自定义环境变量（已配置 ${state.customEnvVars.lines().count { it.isNotBlank() }} 项）"
                        }
                    )
                }

                AppDropdownButton(
                    selectedValue = state.selectedPythonVersion,
                    options = state.pythonVersionOptions,
                    onOptionSelected = onPythonVersionSelect,
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = "Python 版本",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(20.dp)
                                .testTag("settings_python_version_icon")
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    minHeight = 46.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_python_version_dropdown")
                )

                if (state.showPythonWarning) {
                    WarningBannerRow(
                        message = state.pythonWarningText,
                        icon = Icons.Default.Info,
                        iconTag = "settings_python_warning_icon",
                        onClick = onPythonWarningClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_python_warning_row")
                    )
                }

                AppDropdownButton(
                    selectedValue = state.selectedPythonDir,
                    options = state.pythonDirOptions,
                    onOptionSelected = onPythonDirSelect,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    minHeight = 46.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_python_dir_dropdown")
                )

                SettingsPathSelectItem(
                    title = "Python 安装目录 (SAF 选择)",
                    pathValue = state.selectedPythonDir,
                    placeholder = "点击通过 SAF 选择目录",
                    onSelectClick = { pythonDirLauncher.launch(null) },
                    itemTag = "settings_python_dir_saf_item"
                )

                TextButton(
                    onClick = onOpenCustomPathDialog,
                    modifier = Modifier.testTag("settings_custom_path_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = state.customPathButtonText)
                }

                FilledTonalButton(
                    onClick = onReservedSectionClick,
                    modifier = Modifier.testTag("settings_reserved_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = state.reservedButtonLabel)
                }
            }
        }

        // 4. 维护与关于
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_card_about")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "维护与关于",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onCleanTempFiles,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("settings_clean_temp_button")
                    ) {
                        Text(
                            text = "清理缓存 (${state.cacheSizeText})",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    OutlinedButton(
                        onClick = onExportLogs,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("settings_export_logs_button")
                    ) {
                        Text(text = "导出日志")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = onOpenDisclaimerDialog,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("settings_disclaimer_button")
                    ) {
                        Text(text = "查看免责声明")
                    }
                    TextButton(
                        onClick = onOpenLicensesDialog,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("settings_licenses_button")
                    ) {
                        Text(text = "开源协议")
                    }
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_version_row")
                        .clickable(onClick = onVersionRowClick)
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "当前版本",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "v$appVersionName",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // 5. 开发者选项（彩蛋解锁）
        if (state.isDevModeUnlocked) {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_card_developer")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "开发者选项",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    SettingsDropdownItem(
                        title = "Logcat 日志等级",
                        selectedValue = state.logLevel,
                        options = state.logLevelOptions,
                        onOptionSelected = onLogLevelSelect,
                        dropdownTag = "settings_log_level_dropdown"
                    )
                    SettingsSwitchItem(
                        title = "强制软件渲染",
                        subtitle = "禁用硬件图层加速，用于图形兼容性排查",
                        checked = state.forceSoftwareRendering,
                        onCheckedChange = onForceSoftwareRenderingChange,
                        modifier = Modifier.testTag("settings_force_sw_render_switch")
                    )
                }
            }
        }

        if (!state.statusNotice.isNullOrBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = state.statusNotice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(64.dp))
    }

    if (state.isResetContainerDialogOpen) {
        AlertDialog(
            onDismissRequest = onDismissResetContainerDialog,
            title = { Text(text = "重置容器运行配置") },
            text = { Text(text = "确定要将 RootFS、图形渲染、挂载目录及资源限制恢复为默认值吗？") },
            confirmButton = {
                TextButton(
                    onClick = onConfirmResetContainer,
                    modifier = Modifier.testTag("settings_reset_container_confirm")
                ) {
                    Text(text = "确认重置")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissResetContainerDialog) {
                    Text(text = "取消")
                }
            }
        )
    }

    if (state.isEnvVarsDialogOpen) {
        AlertDialog(
            onDismissRequest = onDismissEnvVarsDialog,
            title = { Text(text = "自定义环境变量") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "每行一条，格式为 KEY=VALUE",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = state.envVarsDraft,
                        onValueChange = onEnvVarsDraftChange,
                        isError = state.envVarsError != null,
                        minLines = 3,
                        maxLines = 6,
                        placeholder = { Text("例如：MESA_GL_VERSION_OVERRIDE=4.6") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_env_vars_input")
                    )
                    if (state.envVarsError != null) {
                        Text(
                            text = state.envVarsError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("settings_env_vars_error")
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = onConfirmEnvVars,
                    modifier = Modifier.testTag("settings_env_vars_confirm")
                ) {
                    Text(text = "保存")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissEnvVarsDialog) {
                    Text(text = "取消")
                }
            }
        )
    }

    if (state.isCustomPathDialogOpen) {
        AlertDialog(
            onDismissRequest = onDismissCustomPathDialog,
            title = { Text(text = "输入自定义 Python 路径") },
            text = {
                OutlinedTextField(
                    value = state.customPathDraft,
                    onValueChange = onCustomPathDraftChange,
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_custom_path_input")
                )
            },
            confirmButton = {
                TextButton(
                    onClick = onConfirmCustomPath,
                    modifier = Modifier.testTag("settings_custom_path_confirm")
                ) {
                    Text(text = "确认")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissCustomPathDialog) {
                    Text(text = "取消")
                }
            }
        )
    }

    if (state.isDisclaimerDialogOpen) {
        AlertDialog(
            onDismissRequest = onDismissDisclaimerDialog,
            title = { Text(text = "AICL 使用协议与免责声明") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AiclDisclaimerContent()
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissDisclaimerDialog) {
                    Text(text = "关闭")
                }
            }
        )
    }

    if (state.isLicensesDialogOpen) {
        AlertDialog(
            onDismissRequest = onDismissLicensesDialog,
            title = { Text(text = "开源协议") },
            text = {
                Text(
                    text = "AICL 基于 Jetpack Compose 与 Material 3 构建（MIT License）。" +
                        "运行时仅从官方源动态获取 Trial 版资源，绝不捆绑或镜像分发游戏本体；" +
                        "《Alice In Cradle》版权及 License Terms 归 NanameHacha@Cicini 所有。"
                )
            },
            confirmButton = {
                TextButton(onClick = onDismissLicensesDialog) {
                    Text(text = "关闭")
                }
            }
        )
    }

    if (state.isManualShortCodeDialogOpen) {
        AlertDialog(
            onDismissRequest = onDismissManualShortCodeDialog,
            title = { Text(text = "手动填入官方短码") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "用于验证 302 刷签名链路或外部浏览器捕获兜底",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = state.manualShortCodeDraft,
                        onValueChange = onManualShortCodeDraftChange,
                        label = { Text("短码 (shortCode)") },
                        placeholder = { Text("例如：Oz4hM") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_shortcode_input")
                    )
                    OutlinedTextField(
                        value = state.manualFileNameDraft,
                        onValueChange = onManualFileNameDraftChange,
                        label = { Text("文件名 (fileName)") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_filename_input")
                    )
                    OutlinedTextField(
                        value = state.manualVersionDraft,
                        onValueChange = onManualVersionDraftChange,
                        label = { Text("版本 (可选)") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_version_input")
                    )
                    if (!state.manualShortCodeError.isNullOrBlank()) {
                        Text(
                            text = state.manualShortCodeError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = onConfirmManualShortCode,
                    modifier = Modifier.testTag("settings_shortcode_confirm")
                ) {
                    Text(text = "保存")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissManualShortCodeDialog) {
                    Text(text = "取消")
                }
            }
        )
    }
}
