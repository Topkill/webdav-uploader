package com.webdav.uploader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.webdav.uploader.data.SessionRepository
import com.webdav.uploader.data.UploadHistoryRecord
import com.webdav.uploader.data.UploadHistoryStatus
import com.webdav.uploader.data.UploadSession
import com.webdav.uploader.data.UploadSessionSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    sessions: List<UploadSessionSummary>,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("批次结果") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = { confirmClear = true },
                        enabled = sessions.isNotEmpty(),
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
                .padding(16.dp),
        ) {
            Text(
                text = "每次上传任务的完整成功/失败清单。不受「历史上限」截断。最多保留 ${SessionRepository.MAX_SESSIONS} 个批次。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (sessions.isEmpty()) {
                Text(text = "暂无批次。完成一次上传后会出现在这里。")
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(
                        items = sessions,
                        key = { summary -> summary.id },
                    ) { summary ->
                        SessionSummaryCard(
                            summary = summary,
                            onOpen = { onOpen(summary.id) },
                            onDelete = { onDelete(summary.id) },
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空全部批次？") },
            text = { Text("将删除所有完整批次结果，不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClear()
                        confirmClear = false
                    },
                ) {
                    Text("清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun SessionSummaryCard(
    summary: UploadSessionSummary,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = formatTime(summary.startedAt),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "共 ${summary.total} · 成功 ${summary.success} · 失败 ${summary.failed} · 取消 ${summary.cancelled}",
            )
            Text(
                text = "目录: /${summary.remoteDir}",
                style = MaterialTheme.typography.bodySmall,
            )
            Row {
                TextButton(onClick = onOpen) { Text("查看") }
                TextButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

private enum class SessionFilter {
    ALL,
    SUCCESS,
    FAILED,
    CANCELLED,
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SessionDetailScreen(
    session: UploadSession?,
    onBack: () -> Unit,
    onDeleteSession: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(SessionFilter.ALL) }
    var expandedIds by remember { mutableStateOf(setOf<String>()) }

    val records = session?.records.orEmpty()
    val filtered = remember(records, query, filter) {
        val q = query.trim().lowercase(Locale.getDefault())
        records.filter { record ->
            val statusOk = when (filter) {
                SessionFilter.ALL -> true
                SessionFilter.SUCCESS -> record.status == UploadHistoryStatus.SUCCESS
                SessionFilter.FAILED -> record.status == UploadHistoryStatus.FAILED
                SessionFilter.CANCELLED -> record.status == UploadHistoryStatus.CANCELLED
            }
            if (!statusOk) {
                return@filter false
            }
            if (q.isEmpty()) {
                true
            } else {
                listOf(record.fileName, record.remotePath, record.message)
                    .joinToString(" ")
                    .lowercase(Locale.getDefault())
                    .contains(q)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("批次详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    if (session != null) {
                        TextButton(onClick = { onDeleteSession(session.summary.id) }) {
                            Text("删除批次")
                        }
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
            if (session == null) {
                Text(text = "批次不存在或已删除")
            } else {
                val summary = session.summary
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "开始: ${formatTime(summary.startedAt)}")
                Text(text = "结束: ${formatTime(summary.finishedAt)}")
                Text(
                    text = "共 ${summary.total} · 成功 ${summary.success} · 失败 ${summary.failed} · 取消 ${summary.cancelled}",
                )
                Text(text = "目录: /${summary.remoteDir}")
                Text(
                    text = "此清单完整保存，不受历史上限影响。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("搜索本批次") },
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = filter == SessionFilter.ALL,
                        onClick = { filter = SessionFilter.ALL },
                        label = { Text("全部") },
                    )
                    FilterChip(
                        selected = filter == SessionFilter.SUCCESS,
                        onClick = { filter = SessionFilter.SUCCESS },
                        label = { Text("成功") },
                    )
                    FilterChip(
                        selected = filter == SessionFilter.FAILED,
                        onClick = { filter = SessionFilter.FAILED },
                        label = { Text("失败") },
                    )
                    FilterChip(
                        selected = filter == SessionFilter.CANCELLED,
                        onClick = { filter = SessionFilter.CANCELLED },
                        label = { Text("取消") },
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "显示 ${filtered.size}/${records.size}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(
                        items = filtered,
                        key = { record -> record.id },
                    ) { record ->
                        val expanded = record.id in expandedIds
                        SessionRecordCard(
                            record = record,
                            expanded = expanded,
                            onToggle = {
                                expandedIds = if (expanded) {
                                    expandedIds - record.id
                                } else {
                                    expandedIds + record.id
                                }
                            },
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRecordCard(
    record: UploadHistoryRecord,
    expanded: Boolean,
    onToggle: () -> Unit,
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
            modifier = Modifier.padding(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = record.fileName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "${statusLabel(record.status)} · ${formatSizeLocal(record.fileSize)}",
                        color = statusColor,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(text = "远端: ${record.remotePath.ifBlank { "-" }}")
                    Text(text = "时间: ${formatTime(record.finishedAt)}")
                    if (record.message.isNotBlank()) {
                        Text(text = "备注: ${record.message}")
                    }
                }
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
    if (epochMs <= 0L) return "-"
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(epochMs))
}

private fun formatSizeLocal(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}