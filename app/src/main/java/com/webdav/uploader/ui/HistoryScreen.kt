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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class HistoryFilter {
    ALL,
    SUCCESS,
    FAILED,
    CANCELLED,
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(
    history: List<UploadHistoryRecord>,
    historyMaxItems: Int,
    historyMessage: String?,
    onDelete: (String) -> Unit,
    onDeleteIds: (Collection<String>) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(HistoryFilter.ALL) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var expandedIds by remember { mutableStateOf(setOf<String>()) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    val filtered = remember(history, query, filter) {
        val q = query.trim()
        history.filter { record ->
            val statusOk = when (filter) {
                HistoryFilter.ALL -> true
                HistoryFilter.SUCCESS -> record.status == UploadHistoryStatus.SUCCESS
                HistoryFilter.FAILED -> record.status == UploadHistoryStatus.FAILED
                HistoryFilter.CANCELLED -> record.status == UploadHistoryStatus.CANCELLED
            }
            if (!statusOk) return@filter false
            if (q.isEmpty()) return@filter true
            val haystack = listOf(
                record.fileName,
                record.remotePath,
                record.baseUrl,
                record.message,
                statusLabel(record.status),
            ).joinToString(" ").lowercase(Locale.getDefault())
            haystack.contains(q.lowercase(Locale.getDefault()))
        }
    }

    // 历史列表变化时，清理已不存在的选中项
    LaunchedEffect(history) {
        val valid = history.map { it.id }.toSet()
        selectedIds = selectedIds.intersect(valid)
        expandedIds = expandedIds.intersect(valid)
    }

    val successCount = history.count { it.status == UploadHistoryStatus.SUCCESS }
    val failedCount = history.count { it.status == UploadHistoryStatus.FAILED }
    val cancelledCount = history.count { it.status == UploadHistoryStatus.CANCELLED }

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
                "共 ${history.size}/$historyMaxItems · 成功 $successCount · 失败 $failedCount · 取消 $cancelledCount",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!historyMessage.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(historyMessage, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("搜索文件名/路径/备注") },
                placeholder = { Text("输入关键词") },
            )
            Spacer(Modifier.height(8.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                FilterChip(
                    selected = filter == HistoryFilter.ALL,
                    onClick = { filter = HistoryFilter.ALL },
                    label = { Text("全部") },
                )
                FilterChip(
                    selected = filter == HistoryFilter.SUCCESS,
                    onClick = { filter = HistoryFilter.SUCCESS },
                    label = { Text("成功") },
                )
                FilterChip(
                    selected = filter == HistoryFilter.FAILED,
                    onClick = { filter = HistoryFilter.FAILED },
                    label = { Text("失败") },
                )
                FilterChip(
                    selected = filter == HistoryFilter.CANCELLED,
                    onClick = { filter = HistoryFilter.CANCELLED },
                    label = { Text("取消") },
                )
            }
            Spacer(Modifier.height(8.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        selectedIds = filtered.map { it.id }.toSet()
                    },
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

            Spacer(Modifier.height(8.dp))
            Text(
                "当前显示 ${filtered.size} 条 · 已选 ${selectedIds.size} 条 · 点击条目展开详情",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            if (history.isEmpty()) {
                Text(
                    "暂无历史。上传成功/失败/取消后会自动写入。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (filtered.isEmpty()) {
                Text(
                    "没有符合条件的记录。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(filtered, key = { it.id }) { record ->
                        val expanded = record.id in expandedIds
                        val selected = record.id in selectedIds
                        HistoryCollapsibleCard(
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
                        selectedIds = emptySet()
                        confirmClear = false
                    },
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }

    if (confirmDeleteSelected) {
        AlertDialog(
            onDismissRequest = { confirmDeleteSelected = false },
            title = { Text("删除所选？") },
            text = { Text("将删除 ${selectedIds.size} 条记录，不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteIds(selectedIds)
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
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("删除这条历史？") },
            text = { Text("删除后不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(id)
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

@Composable
private fun HistoryCollapsibleCard(
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
                        record.fileName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${statusLabel(record.status)} · ${formatSize(record.fileSize)} · ${formatTime(record.finishedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor,
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
                    modifier = Modifier.padding(start = 12.dp, end = 8.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("远端: ${record.remotePath.ifBlank { "-" }}")
                    Text("服务器: ${record.baseUrl.ifBlank { "-" }}")
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