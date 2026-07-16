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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { confirmClear = true },
                        enabled = sessions.isNotEmpty(),
                    ) { Text("清空") }
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
                "每次上传任务的完整成功/失败清单。不受「历史上限」截断。最多保留 ${com.webdav.uploader.data.SessionRepository.MAX_SESSIONS} 个批次。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            if (sessions.isEmpty()) {
                Text("暂无批次。完成一次上传后会出现在这里。")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sessions, key = { it.id }) { s ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(s.id) },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        ) {
                            Column(Modifier = Modifier.padding(12.dp)) {
                                Text(
                                    formatTime(s.startedAt),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text("共 ${s.total} · 成功 ${s.success} · 失败 ${s.failed} · 取消 ${s.cancelled}")
                                Text(
                                    "目录: /${s.remoteDir.ifBlank { "" }}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Row {
                                    TextButton(onClick = { onOpen(s.id) }) { Text("查看") }
                                    TextButton(onClick = { onDelete(s.id) }) { Text("删除") }
                                }
                            }
                        }
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
                TextButton(onClick = { onClear(); confirmClear = false }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

private enum class SessionFilter { ALL, SUCCESS, FAILED, CANCELLED }

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
        records.filter { r ->
            val ok = when (filter) {
                SessionFilter.ALL -> true
                SessionFilter.SUCCESS -> r.status == UploadHistoryStatus.SUCCESS
                SessionFilter.FAILED -> r.status == UploadHistoryStatus.FAILED
                SessionFilter.CANCELLED -> r.status == UploadHistoryStatus.CANCELLED
            }
            if (!ok) return@filter false
            if (q.isEmpty()) true
            else listOf(r.fileName, r.remotePath, r.message).joinToString(" ")
                .lowercase(Locale.getDefault()).contains(q)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("批次详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                Text("批次不存在或已删除")
                return@Column
            }
            val s = session.summary
            Spacer(Modifier.height(8.dp))
            Text("开始: ${formatTime(s.startedAt)}")
            Text("结束: ${formatTime(s.finishedAt)}")
            Text("共 ${s.total} · 成功 ${s.success} · 失败 ${s.failed} · 取消 ${s.cancelled}")
            Text("目录: /${s.remoteDir}")
            Text(
                "此清单完整保存，不受历史上限影响。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("搜索本批次") },
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = filter == SessionFilter.ALL, onClick = { filter = SessionFilter.ALL }, label = { Text("全部") })
                FilterChip(selected = filter == SessionFilter.SUCCESS, onClick = { filter = SessionFilter.SUCCESS }, label = { Text("成功") })
                FilterChip(selected = filter == SessionFilter.FAILED, onClick = { filter = SessionFilter.FAILED }, label = { Text("失败") })
                FilterChip(selected = filter == SessionFilter.CANCELLED, onClick = { filter = SessionFilter.CANCELLED }, label = { Text("取消") })
            }
            Spacer(Modifier.height(8.dp))
            Text("显示 ${filtered.size}/${records.size}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filtered, key = { it.id }) { r ->
                    val expanded = r.id in expandedIds
                    SessionRecordCard(
                        record = r,
                        expanded = expanded,
                        onToggle = {
                            expandedIds = if (expanded) expandedIds - r.id else expandedIds + r.id
                        },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
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
    val color = when (record.status) {
        UploadHistoryStatus.SUCCESS -> Color(0xFF2E7D32)
        UploadHistoryStatus.FAILED -> MaterialTheme.colorScheme.error
        UploadHistoryStatus.CANCELLED -> Color(0xFFEF6C00)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(record.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${statusLabel(record.status)} · ${formatSize(record.fileSize)}",
                        color = color,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("远端: ${record.remotePath.ifBlank { "-" }}")
                    Text("时间: ${formatTime(record.finishedAt)}")
                    if (record.message.isNotBlank()) Text("备注: ${record.message}")
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
    if (epochMs <= 0) return "-"
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(epochMs))
}