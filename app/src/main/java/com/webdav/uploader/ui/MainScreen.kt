package com.webdav.uploader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.webdav.uploader.data.WebDavConfig
import com.webdav.uploader.upload.UploadUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    config: WebDavConfig,
    upload: UploadUiState,
    dataPath: String,
    onPickFiles: () -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRecords: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WebDAV 上传器") },
                actions = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("设置") },
                            onClick = {
                                menuExpanded = false
                                onOpenSettings()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("上传记录") },
                            onClick = {
                                menuExpanded = false
                                onOpenRecords()
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("当前连接", style = MaterialTheme.typography.titleMedium)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("URL: ${config.baseUrl.ifBlank { "未设置" }}")
                    Text("用户: ${config.username.ifBlank { "未设置" }}")
                    Text("远端目录: /${config.remoteDir.ifBlank { "" }}")
                    Text("读超时: ${config.readTimeoutSec}s")
                    Text(
                        "可在右上角「更多 → 设置」修改。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "数据目录(可直接访问，无需 root):\n$dataPath",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text("上传", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onPickFiles,
                    enabled = !upload.running,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (upload.running) "上传中..." else "选择文件上传")
                }
                OutlinedButton(
                    onClick = onCancel,
                    enabled = upload.running,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("取消")
                }
            }

            StatusCard(upload)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun StatusCard(upload: UploadUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("状态: ${upload.phase}", style = MaterialTheme.typography.titleSmall)
            if (upload.totalCount > 0 &&
                (upload.successCount + upload.failedCount + upload.cancelledCount) > 0
            ) {
                Text(
                    "累计: 成功 ${upload.successCount} / 失败 ${upload.failedCount} / 取消 ${upload.cancelledCount}",
                )
            }
            if (upload.totalCount > 0) {
                Text("文件: ${upload.currentIndex}/${upload.totalCount}  ${upload.currentName}")
            }
            if (upload.totalBytes > 0) {
                val percent = (upload.bytesSent.toFloat() / upload.totalBytes).coerceIn(0f, 1f)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { percent },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${formatSize(upload.bytesSent)} / ${formatSize(upload.totalBytes)} " +
                        "(${(percent * 100).toInt()}%)",
                )
            }
            if (!upload.lastError.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "错误: ${upload.lastError}",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (upload.log.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text("日志", style = MaterialTheme.typography.labelLarge)
                Text(
                    upload.log,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
}

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format("%.2f GB", gb)
}