package com.webdav.uploader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.webdav.uploader.data.UploadHistoryRecord
import com.webdav.uploader.data.UploadHistoryStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    history: List<UploadHistoryRecord>,
    historyMaxItems: Int,
    historyMessage: String?,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("上传历史") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { confirmClear = true },
                        enabled = history.isNotEmpty(),
                    ) {
                        Text("清空")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                "仅支持查看与删除。当前 ${history.size}/$historyMaxItems 条（上限在设置中修改）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!historyMessage.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(historyMessage, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))

            if (history.isEmpty()) {
                Text(
                    "暂无历史。上传成功/失败/取消后会自动写入。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(history, key = { it.id }) { record ->
                        HistoryReadOnlyCard(
                            record = record,
                            onDelete = { pendingDeleteId = record.id },
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空历史？") },
            text = { Text("将删除全部 ${history.size} 条记录，不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClear()
                        confirmClear = false
                    },
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }

    pendingDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("删除这条历史？") },
            text = { Text("删除后不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(id)
                        pendingDeleteId = null
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun HistoryReadOnlyCard(
    record: UploadHistoryRecord,
    onDelete: () -> Unit,
) {
    val statusColor = when (record.status) {
        UploadHistoryStatus.SUCCESS -> Color(0xFF2E7D32)
        UploadHistoryStatus.FAILED -> MaterialTheme.colorScheme.error
        UploadHistoryStatus.CANCELLED -> Color(0xFFEF6C00)
    }
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
            Text(record.fileName, style = MaterialTheme.typography.titleSmall)
            Text(
                statusLabel(record.status),
                color = statusColor,
                style = MaterialTheme.typography.labelLarge,
            )
            Text("大小: ${formatSize(record.fileSize)}")
            Text("远端: ${record.remotePath.ifBlank { "-" }}")
            Text("服务器: ${record.baseUrl.ifBlank { "-" }}")
            Text("时间: ${formatTime(record.finishedAt)}")
            Text("耗时: ${formatDuration(record.durationMs)}")
            if (record.message.isNotBlank()) {
                Text("备注: ${record.message}")
            }
            Row {
                OutlinedButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

private fun statusLabel(status: UploadHistoryStatus): String = when (status) {
    UploadHistoryStatus.SUCCESS -> "成功"
    UploadHistoryStatus.FAILED -> "失败"
    UploadHistoryStatus.CANCELLED -> "已取消"
}

private fun formatTime(epochMs: Long): String {
    if (epochMs <= 0) return "-"
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(epochMs))
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0s"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "${h}h ${m}m ${s}s"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}