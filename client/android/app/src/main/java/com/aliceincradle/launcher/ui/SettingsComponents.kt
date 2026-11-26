package com.aliceincradle.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aliceincradle.launcher.data.SettingsPageState

@Composable
fun SettingsScreen(
    state: SettingsPageState,
    onPythonVersionSelect: (String) -> Unit,
    onPythonWarningClick: () -> Unit,
    onPythonDirSelect: (String) -> Unit,
    onOpenCustomPathDialog: () -> Unit,
    onCustomPathDraftChange: (String) -> Unit,
    onConfirmCustomPath: () -> Unit,
    onDismissCustomPathDialog: () -> Unit,
    onReservedSectionClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Python 设置",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("settings_python_card_title")
                )

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

                state.statusNotice?.let { notice ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_status_notice"),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                    ) {
                        Text(
                            text = notice,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onReservedSectionClick,
                    modifier = Modifier.testTag("settings_reserved_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = state.reservedButtonLabel)
                }

                Text(
                    text = state.reservedHintText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("settings_reserved_hint")
                )
            }
        }

        Spacer(modifier = Modifier.height(72.dp))
    }

    if (state.isCustomPathDialogOpen) {
        AlertDialog(
            onDismissRequest = onDismissCustomPathDialog,
            title = {
                Text(text = "自定义路径选择", style = MaterialTheme.typography.titleMedium)
            },
            text = {
                OutlinedTextField(
                    value = state.customPathDraft,
                    onValueChange = onCustomPathDraftChange,
                    label = { Text(text = "Python 安装目录（展示预设）") },
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
                    Text(text = "确定")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissCustomPathDialog) {
                    Text(text = "取消")
                }
            }
        )
    }
}
