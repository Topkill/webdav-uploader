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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

/**
 * 上传记录：默认按任务/批次列表。
 * 无任务数上限；用户可删除单个任务或清空全部。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadRecordsScreen(
    sessions: List<UploadSessionSummary>,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("上传记录") },
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
                text = "按上传任务查看完整成功/失败清单。任务数量无上限，可手动删除。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (sessions.isEmpty()) {
                Text(text = "暂无记录。完成一次上传后会出现在这里。")
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(
                        items = sessions,
                        key = { it.id },
                    ) { summary ->
                        TaskSummaryCard(
                            summary = summary,
                            onOpen = { onOpen(summary.id) },
                            onDelete = { pendingDeleteId = summary.id },
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空全部上传记录？") },
            text = { Text("将删除所有任务的完整结果，不可恢复。") },
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
            title = { Text("删除这个任务？") },
            text = { Text("将删除该任务的全部文件结果，不可恢复。") },
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
private fun TaskSummaryCard(
    summary: UploadSessionSummary,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val statusText = when {
        summary.total == 0 -> "进行中/空"
        summary.failed == 0 && summary.cancelled == 0 -> "全部成功"
        summary.success == 0 && summary.failed > 0 -> "全部失败"
        else -> "部分失败"
    }
    val statusColor = when {
        summary.total > 0 && summary.failed == 0 && summary.cancelled == 0 -> Color(0xFF2E7D32)
        summary.failed > 0 -> MaterialTheme.colorScheme.error
        summary.cancelled > 0 -> Color(0xFFEF6C00)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

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
                text = statusText,
                color = statusColor,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = "共 ${summary.total} · 成功 ${summary.success} · 失败 ${summary.failed} · 取消 ${summary.cancelled}",
            )
            Text(
                text = "目录: /${summary.remoteDir}",
                style = MaterialTheme.typography.bodySmall,
            )
            Row {
                TextButton(onClick = onOpen) { Text("查看明细") }
                TextButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

private enum class RecordFilter {
    ALL,
    SUCCESS,
    FAILED,
    CANCELLED,
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun UploadRecordDetailScreen(
    session: UploadSession?,
    onBack: () -> Unit,
    onDeleteSession: (String) -> Unit,
    onDeleteRecords: (sessionId: String, recordIds: Collection<String>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(RecordFilter.ALL) }
    var expandedIds by remember { mutableStateOf(setOf<String>()) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    val records = session?.records.orEmpty()
    val filtered = remember(records, query, filter) {
        val q = query.trim().lowercase(Locale.getDefault())
        records.filter { record ->
            val statusOk = when (filter) {
                RecordFilter.ALL -> true
                RecordFilter.SUCCESS -> record.status == UploadHistoryStatus.SUCCESS
                RecordFilter.FAILED -> record.status == UploadHistoryStatus.FAILED
                RecordFilter.CANCELLED -> record.status == UploadHistoryStatus.CANCELLED
            }
            if (!statusOk) return@filter false
            if (q.isEmpty()) true
            else {
                listOf(record.fileName, record.remotePath, record.message)
                    .joinToString(" ")
                    .lowercase(Locale.getDefault())
                    .contains(q)
            }
        }
    }

    LaunchedEffect(records) {
        val valid = records.map { it.id }.toSet()
        selectedIds = selectedIds.intersect(valid)
        expandedIds = expandedIds.intersect(valid)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("任务明细") },
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
                        TextButton(onClick = { confirmDelete = true }) {
                            Text("删除任务")
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
                Text(text = "记录不存在或已删除")
            } else {
                val summary = session.summary
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "开始: ${formatTime(summary.startedAt)}")
                Text(text = "结束: ${formatTime(summary.finishedAt)}")
                Text(
                    text = "共 ${summary.total} · 成功 ${summary.success} · 失败 ${summary.failed} · 取消 ${summary.cancelled}",
                )
                Text(text = "目录: /${summary.remoteDir}")
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("搜索本任务文件") },
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = filter == RecordFilter.ALL,
                        onClick = { filter = RecordFilter.ALL },
                        label = { Text("全部") },
                    )
                    FilterChip(
                        selected = filter == RecordFilter.SUCCESS,
                        onClick = { filter = RecordFilter.SUCCESS },
                        label = { Text("成功") },
                    )
                    FilterChip(
                        selected = filter == RecordFilter.FAILED,
                        onClick = { filter = RecordFilter.FAILED },
                        label = { Text("失败") },
                    )
                    FilterChip(
                        selected = filter == RecordFilter.CANCELLED,
                        onClick = { filter = RecordFilter.CANCELLED },
                        label = { Text("取消") },
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { selectedIds = filtered.map { it.id }.toSet() },
                        enabled = filtered.isNotEmpty(),
                    ) { Text("全选") }
                    OutlinedButton(
                        onClick = {
                            val visible = filtered.map { it.id }.toSet()
                            selectedIds = visible - selectedIds
                        },
                        enabled = filtered.isNotEmpty(),
                    ) { Text("反选") }
                    OutlinedButton(
                        onClick = { selectedIds = emptySet() },
                        enabled = selectedIds.isNotEmpty(),
                    ) { Text("取消选择") }
                    OutlinedButton(
                        onClick = { confirmDeleteSelected = true },
                        enabled = selectedIds.isNotEmpty(),
                    ) { Text("删除所选(${selectedIds.size})") }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "显示 ${filtered.size}/${records.size} · 已选 ${selectedIds.size} · 点击条目展开详情",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (filtered.isEmpty()) {
                    Text(
                        text = if (records.isEmpty()) "本任务暂无文件记录。" else "没有符合条件的记录。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(
                            items = filtered,
                            key = { it.id },
                        ) { record ->
                            val expanded = record.id in expandedIds
                            val selected = record.id in selectedIds
                            RecordRowCard(
                                record = record,
                                expanded = expanded,
                                selected = selected,
                                onToggleExpand = {
                                    expandedIds = if (expanded) {
                                        expandedIds - record.id
                                    } else {
                                        expandedIds + record.id
                                    }
                                },
                                onToggleSelect = {
                                    selectedIds = if (selected) {
                                        selectedIds - record.id
                                    } else {
                                        selectedIds + record.id
                                    }
                                },
                                onDelete = { pendingDeleteId = record.id },
                            )
                        }
                        item { Spacer(modifier = Modifier.height(24.dp)) }
                    }
                }
            }
        }
    }

    if (confirmDelete && session != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这个任务？") },
            text = { Text("将删除该任务全部 ${session.summary.total} 条结果，不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteSession(session.summary.id)
                        confirmDelete = false
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }

    if (confirmDeleteSelected && session != null) {
        AlertDialog(
            onDismissRequest = { confirmDeleteSelected = false },
            title = { Text("删除所选？") },
            text = { Text("将删除 ${selectedIds.size} 条记录，不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteRecords(session.summary.id, selectedIds)
                        selectedIds = emptySet()
                        confirmDeleteSelected = false
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteSelected = false }) { Text("取消") }
            },
        )
    }

    pendingDeleteId?.let { id ->
        val sid = session?.summary?.id
        if (sid != null) {
            AlertDialog(
                onDismissRequest = { pendingDeleteId = null },
                title = { Text("删除这条记录？") },
                text = { Text("删除后不可恢复。") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onDeleteRecords(sid, listOf(id))
                            selectedIds = selectedIds - id
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
}
@Composable
private fun RecordRowCard(
    record: UploadHistoryRecord,
    expanded: Boolean,
    selected: Boolean,
    onToggleExpand: () -> Unit,
    onToggleSelect: () -> Unit,
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
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelect() },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = record.fileName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "${statusLabel(record.status)} · ${formatSizeLocal(record.fileSize)} · ${formatTime(record.finishedAt)}",
                        color = statusColor,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(start = 12.dp, end = 8.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(text = "远端: ${record.remotePath.ifBlank { "-" }}")
                    Text(text = "服务器: ${record.baseUrl.ifBlank { "-" }}")
                    Text(text = "耗时: ${formatDuration(record.durationMs)}")
                    if (record.message.isNotBlank()) {
                        Text(text = "备注: ${record.message}")
                    }
                    OutlinedButton(onClick = onDelete) { Text("删除") }
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

private fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "0s"
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

private fun formatSizeLocal(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}