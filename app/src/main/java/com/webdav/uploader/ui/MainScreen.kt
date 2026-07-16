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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.webdav.uploader.data.WebDavConfig
import com.webdav.uploader.upload.UploadUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    config: WebDavConfig,
    upload: UploadUiState,
    probeMessage: String?,
    onConfigChange: ((WebDavConfig) -> WebDavConfig) -> Unit,
    onSave: () -> Unit,
    onProbe: () -> Unit,
    onPickFiles: () -> Unit,
    onCancel: () -> Unit,
) {
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
        }
    }
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

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format("%.2f GB", gb)
}
