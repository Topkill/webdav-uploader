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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.webdav.uploader.data.HistoryRepository
import com.webdav.uploader.data.UploadHistoryRecord
import com.webdav.uploader.data.UploadHistoryStatus
import com.webdav.uploader.data.WebDavConfig
import com.webdav.uploader.upload.UploadUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    config: WebDavConfig,
    upload: UploadUiState,
    probeMessage: String?,
    history: List<UploadHistoryRecord>,
    historyMaxItems: Int,
    historyMessage: String?,
    onConfigChange: ((WebDavConfig) -> WebDavConfig) -> Unit,
    onSave: () -> Unit,
    onProbe: () -> Unit,
    onPickFiles: () -> Unit,
    onCancel: () -> Unit,
    onSetHistoryMaxItems: (Int) -> Unit,
    onAddHistory: (fileName: String, fileSize: Long, remotePath: String, status: UploadHistoryStatus, message: String) -> Unit,
    onUpdateHistory: (UploadHistoryRecord) -> Unit,
    onDeleteHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
) {
    var editing by remember { mutableStateOf<UploadHistoryRecord?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var maxItemsText by remember(historyMaxItems) { mutableStateOf(historyMaxItems.toString()) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("WebDAV 上传器") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("连接设置", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = config.baseUrl,
                onValueChange = { v -> onConfigChange { it.copy(baseUrl = v) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("WebDAV URL") },
                placeholder = { Text("http://192.168.x.x:5244/dav") },
                singleLine = true,
            )
            OutlinedTextField(
                value = config.username,
                onValueChange = { v -> onConfigChange { it.copy(username = v) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("用户名") },
                singleLine = true,
            )
            OutlinedTextField(
                value = config.password,
                onValueChange = { v -> onConfigChange { it.copy(password = v) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            OutlinedTextField(
                value = config.remoteDir,
                onValueChange = { v -> onConfigChange { it.copy(remoteDir = v) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("远端目录（crypt 路径）") },
                placeholder = { Text("crypt 或 加密盘/手机") },
                singleLine = true,
            )

            Text("超时设置（秒）", style = MaterialTheme.typography.titleMedium)
            Text(
                "读超时 = 上传发完后，等待 OpenList crypt/网盘返回最终结果的时间。默认 1800 秒（30 分钟）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            TimeoutField(
                label = "连接超时 connect",
                value = config.connectTimeoutSec,
                onChange = { v -> onConfigChange { it.copy(connectTimeoutSec = v) } },
            )
            TimeoutField(
                label = "读超时 read（等最终响应）",
                value = config.readTimeoutSec,
                onChange = { v -> onConfigChange { it.copy(readTimeoutSec = v) } },
            )
            TimeoutField(
                label = "写超时 write（0=不限制）",
                value = config.writeTimeoutSec,
                onChange = { v -> onConfigChange { it.copy(writeTimeoutSec = v) } },
            )
            TimeoutField(
                label = "整体超时 call（0=不限制）",
                value = config.callTimeoutSec,
                onChange = { v -> onConfigChange { it.copy(callTimeoutSec = v) } },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave, modifier = Modifier.weight(1f)) {
                    Text("保存设置")
                }
                OutlinedButton(onClick = onProbe, modifier = Modifier.weight(1f)) {
                    Text("测试连接")
                }
            }

            if (!probeMessage.isNullOrBlank()) {
                Text(probeMessage, style = MaterialTheme.typography.bodyMedium)
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

            // -------- 上传历史 --------
            Text("上传历史", style = MaterialTheme.typography.titleMedium)
            Text(
                "已落盘保存。超出上限会自动丢掉最旧记录。当前 ${history.size}/$historyMaxItems 条。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = maxItemsText,
                    onValueChange = { maxItemsText = it.filter { ch -> ch.isDigit() }.take(3) },
                    modifier = Modifier.weight(1f),
                    label = { Text("历史上限(10-500)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Button(
                    onClick = {
                        val n = maxItemsText.toIntOrNull()
                            ?: HistoryRepository.DEFAULT_MAX_ITEMS
                        onSetHistoryMaxItems(
                            n.coerceIn(HistoryRepository.MIN_MAX_ITEMS, HistoryRepository.MAX_MAX_ITEMS),
                        )
                    },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("保存上限")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { adding = true }, modifier = Modifier.weight(1f)) {
                    Text("手动新增")
                }
                OutlinedButton(
                    onClick = { confirmClear = true },
                    enabled = history.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("清空历史")
                }
            }

            if (!historyMessage.isNullOrBlank()) {
                Text(historyMessage, style = MaterialTheme.typography.bodyMedium)
            }

            if (history.isEmpty()) {
                Text(
                    "暂无历史。上传成功/失败后会自动写入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                history.forEach { record ->
                    HistoryCard(
                        record = record,
                        onEdit = { editing = record },
                        onDelete = { onDeleteHistory(record.id) },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (adding) {
        HistoryEditorDialog(
            title = "新增历史",
            initial = UploadHistoryRecord(
                fileName = "",
                fileSize = 0,
                remotePath = config.remoteDir,
                baseUrl = config.baseUrl,
                status = UploadHistoryStatus.SUCCESS,
                message = "",
                startedAt = System.currentTimeMillis(),
                finishedAt = System.currentTimeMillis(),
            ),
            onDismiss = { adding = false },
            onConfirm = { draft ->
                onAddHistory(
                    draft.fileName,
                    draft.fileSize,
                    draft.remotePath,
                    draft.status,
                    draft.message,
                )
                adding = false
            },
        )
    }

    editing?.let { target ->
        HistoryEditorDialog(
            title = "编辑历史",
            initial = target,
            onDismiss = { editing = null },
            onConfirm = { draft ->
                onUpdateHistory(draft)
                editing = null
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空历史？") },
            text = { Text("将删除全部 ${history.size} 条落盘记录，不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearHistory()
                        confirmClear = false
                    },
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun HistoryCard(
    record: UploadHistoryRecord,
    onEdit: () -> Unit,
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit) { Text("编辑") }
                OutlinedButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryEditorDialog(
    title: String,
    initial: UploadHistoryRecord,
    onDismiss: () -> Unit,
    onConfirm: (UploadHistoryRecord) -> Unit,
) {
    var fileName by remember(initial.id) { mutableStateOf(initial.fileName) }
    var fileSizeText by remember(initial.id) { mutableStateOf(initial.fileSize.toString()) }
    var remotePath by remember(initial.id) { mutableStateOf(initial.remotePath) }
    var baseUrl by remember(initial.id) { mutableStateOf(initial.baseUrl) }
    var message by remember(initial.id) { mutableStateOf(initial.message) }
    var status by remember(initial.id) { mutableStateOf(initial.status) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text("文件名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = fileSizeText,
                    onValueChange = { fileSizeText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("大小(字节)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = remotePath,
                    onValueChange = { remotePath = it },
                    label = { Text("远端路径") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("WebDAV URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("状态", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UploadHistoryStatus.entries.forEach { s ->
                        if (status == s) {
                            Button(onClick = { status = s }) { Text(statusLabel(s)) }
                        } else {
                            OutlinedButton(onClick = { status = s }) { Text(statusLabel(s)) }
                        }
                    }
                }
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("备注/错误信息") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val now = System.currentTimeMillis()
                    onConfirm(
                        initial.copy(
                            fileName = fileName,
                            fileSize = fileSizeText.toLongOrNull() ?: 0L,
                            remotePath = remotePath,
                            baseUrl = baseUrl,
                            status = status,
                            message = message,
                            // 手动新增/编辑时保留原时间；若新建 started/finished 已是 now
                            finishedAt = if (initial.finishedAt <= 0) now else initial.finishedAt,
                            startedAt = if (initial.startedAt <= 0) now else initial.startedAt,
                        ),
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun TimeoutField(
    label: String,
    value: Long,
    onChange: (Long) -> Unit,
) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { text ->
            val v = text.filter { it.isDigit() }
            onChange(v.toLongOrNull() ?: 0L)
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun StatusCard(upload: UploadUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("状态: ${upload.phase}", style = MaterialTheme.typography.titleSmall)
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

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format("%.2f GB", gb)
}