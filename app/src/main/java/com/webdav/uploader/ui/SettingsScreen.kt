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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.webdav.uploader.data.HistoryRepository
import com.webdav.uploader.data.WebDavConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    draft: WebDavConfig,
    historyMaxDraft: Int,
    settingsMessage: String?,
    probeMessage: String?,
    onDraftChange: ((WebDavConfig) -> WebDavConfig) -> Unit,
    onHistoryMaxChange: (Int) -> Unit,
    onSave: () -> Unit,
    onProbe: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
            Text("连接配置", style = MaterialTheme.typography.titleMedium)
            Text(
                "以下配置会落盘保存，重启 App 后仍然有效。上传使用已保存配置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = draft.baseUrl,
                onValueChange = { v -> onDraftChange { it.copy(baseUrl = v) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("WebDAV URL") },
                placeholder = { Text("http://192.168.x.x:5244/dav") },
                singleLine = true,
            )
            OutlinedTextField(
                value = draft.username,
                onValueChange = { v -> onDraftChange { it.copy(username = v) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("用户名") },
                singleLine = true,
            )
            OutlinedTextField(
                value = draft.password,
                onValueChange = { v -> onDraftChange { it.copy(password = v) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            OutlinedTextField(
                value = draft.remoteDir,
                onValueChange = { v -> onDraftChange { it.copy(remoteDir = v) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("远端目录（crypt 路径）") },
                placeholder = { Text("crypt 或 加密盘/手机") },
                singleLine = true,
            )

            Text("超时（秒）", style = MaterialTheme.typography.titleMedium)
            Text(
                "读超时 = 发完数据后等待最终响应的时间（OpenList crypt 场景重点）。写/整体超时填 0 表示不限制。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TimeoutField(
                label = "连接超时 connect",
                value = draft.connectTimeoutSec,
                onChange = { v -> onDraftChange { it.copy(connectTimeoutSec = v) } },
            )
            TimeoutField(
                label = "读超时 read（等最终响应）",
                value = draft.readTimeoutSec,
                onChange = { v -> onDraftChange { it.copy(readTimeoutSec = v) } },
            )
            TimeoutField(
                label = "写超时 write（0=不限制）",
                value = draft.writeTimeoutSec,
                onChange = { v -> onDraftChange { it.copy(writeTimeoutSec = v) } },
            )
            TimeoutField(
                label = "整体超时 call（0=不限制）",
                value = draft.callTimeoutSec,
                onChange = { v -> onDraftChange { it.copy(callTimeoutSec = v) } },
            )

            Text("历史上限", style = MaterialTheme.typography.titleMedium)
            Text(
                "范围 ${HistoryRepository.MIN_MAX_ITEMS}–${HistoryRepository.MAX_MAX_ITEMS}，超出自动丢弃最旧记录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = historyMaxDraft.toString(),
                onValueChange = { text ->
                    val n = text.filter { it.isDigit() }.toIntOrNull()
                        ?: HistoryRepository.MIN_MAX_ITEMS
                    onHistoryMaxChange(n)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("上传历史上限条数") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave, modifier = Modifier.weight(1f)) {
                    Text("保存设置")
                }
                OutlinedButton(onClick = onProbe, modifier = Modifier.weight(1f)) {
                    Text("测试连接")
                }
            }

            if (!settingsMessage.isNullOrBlank()) {
                Text(settingsMessage, color = MaterialTheme.colorScheme.primary)
            }
            if (!probeMessage.isNullOrBlank()) {
                Text(probeMessage)
            }
            Spacer(Modifier.height(24.dp))
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