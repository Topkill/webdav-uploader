package com.webdav.uploader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.webdav.uploader.data.HistoryRepository
import com.webdav.uploader.data.SettingsRepository
import com.webdav.uploader.data.UploadHistoryRecord
import com.webdav.uploader.data.UploadHistoryStatus
import com.webdav.uploader.data.WebDavConfig
import com.webdav.uploader.webdav.WebDavClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settingsRepo = SettingsRepository(app)
    private val historyRepo = HistoryRepository(app)

    private val _config = MutableStateFlow(WebDavConfig())
    val config: StateFlow<WebDavConfig> = _config.asStateFlow()

    private val _probeMessage = MutableStateFlow<String?>(null)
    val probeMessage: StateFlow<String?> = _probeMessage.asStateFlow()

    private val _historyMessage = MutableStateFlow<String?>(null)
    val historyMessage: StateFlow<String?> = _historyMessage.asStateFlow()

    val history: StateFlow<List<UploadHistoryRecord>> = historyRepo.historyFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val historyMaxItems: StateFlow<Int> = historyRepo.maxItemsFlow
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            HistoryRepository.DEFAULT_MAX_ITEMS,
        )

    private val _dirty = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            settingsRepo.configFlow.collect { saved ->
                if (!_dirty.value) {
                    _config.value = saved
                }
            }
        }
    }

    fun updateLocal(transform: (WebDavConfig) -> WebDavConfig) {
        _dirty.value = true
        _config.value = transform(_config.value)
    }

    fun save() {
        viewModelScope.launch {
            settingsRepo.save(_config.value)
            _dirty.value = false
            _probeMessage.value = "设置已保存"
        }
    }

    fun probe() {
        viewModelScope.launch {
            _probeMessage.value = "正在测试连接..."
            settingsRepo.save(_config.value)
            _dirty.value = false
            val result = WebDavClient(_config.value).probe()
            _probeMessage.value = result.fold(
                onSuccess = { it },
                onFailure = { "连接失败: ${it.message}" },
            )
        }
    }

    fun setHistoryMaxItems(maxItems: Int) {
        viewModelScope.launch {
            historyRepo.setMaxItems(maxItems)
            _historyMessage.value = "历史上限已设为 ${maxItems.coerceIn(HistoryRepository.MIN_MAX_ITEMS, HistoryRepository.MAX_MAX_ITEMS)} 条"
        }
    }

    fun addHistory(
        fileName: String,
        fileSize: Long,
        remotePath: String,
        status: UploadHistoryStatus,
        message: String,
        baseUrl: String = _config.value.baseUrl,
    ) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            historyRepo.add(
                UploadHistoryRecord(
                    fileName = fileName.trim().ifBlank { "未命名" },
                    fileSize = fileSize.coerceAtLeast(0),
                    remotePath = remotePath.trim(),
                    baseUrl = baseUrl.trim(),
                    status = status,
                    message = message.trim(),
                    startedAt = now,
                    finishedAt = now,
                ),
            )
            _historyMessage.value = "已新增历史记录"
        }
    }

    fun updateHistory(record: UploadHistoryRecord) {
        viewModelScope.launch {
            val ok = historyRepo.update(
                record.copy(
                    fileName = record.fileName.trim().ifBlank { "未命名" },
                    remotePath = record.remotePath.trim(),
                    baseUrl = record.baseUrl.trim(),
                    message = record.message.trim(),
                    fileSize = record.fileSize.coerceAtLeast(0),
                    durationMs = (record.finishedAt - record.startedAt).coerceAtLeast(0),
                ),
            )
            _historyMessage.value = if (ok) "已更新历史记录" else "更新失败：记录不存在"
        }
    }

    fun deleteHistory(id: String) {
        viewModelScope.launch {
            val ok = historyRepo.delete(id)
            _historyMessage.value = if (ok) "已删除" else "删除失败：记录不存在"
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            historyRepo.clear()
            _historyMessage.value = "历史已清空"
        }
    }
}