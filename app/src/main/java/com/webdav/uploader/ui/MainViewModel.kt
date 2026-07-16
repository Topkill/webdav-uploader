package com.webdav.uploader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.webdav.uploader.data.HistoryRepository
import com.webdav.uploader.data.SettingsRepository
import com.webdav.uploader.data.UploadHistoryRecord
import com.webdav.uploader.data.WebDavConfig
import com.webdav.uploader.keepalive.KeepAliveHelper
import com.webdav.uploader.keepalive.KeepAliveStatus
import com.webdav.uploader.webdav.WebDavClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class AppScreen {
    data object Home : AppScreen()
    data object Settings : AppScreen()
    data object History : AppScreen()
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settingsRepo = SettingsRepository(app)
    private val historyRepo = HistoryRepository(app)

    private val _config = MutableStateFlow(WebDavConfig())
    val config: StateFlow<WebDavConfig> = _config.asStateFlow()

    /** 已落盘的配置，供主页展示/上传读取一致性提示 */
    val savedConfig: StateFlow<WebDavConfig> = settingsRepo.configFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WebDavConfig())

    private val _probeMessage = MutableStateFlow<String?>(null)
    val probeMessage: StateFlow<String?> = _probeMessage.asStateFlow()

    private val _settingsMessage = MutableStateFlow<String?>(null)
    val settingsMessage: StateFlow<String?> = _settingsMessage.asStateFlow()

    private val _historyMessage = MutableStateFlow<String?>(null)

    private val _keepAliveStatus = MutableStateFlow(
        KeepAliveStatus(
            batteryOptimizationIgnored = false,
            notificationGranted = false,
            canRequestBatteryOptimization = true,
        ),
    )
    val keepAliveStatus: StateFlow<KeepAliveStatus> = _keepAliveStatus.asStateFlow()
    val historyMessage: StateFlow<String?> = _historyMessage.asStateFlow()

    private val _screen = MutableStateFlow<AppScreen>(AppScreen.Home)
    val screen: StateFlow<AppScreen> = _screen.asStateFlow()

    val history: StateFlow<List<UploadHistoryRecord>> = historyRepo.historyFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val historyMaxItems: StateFlow<Int> = historyRepo.maxItemsFlow
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            HistoryRepository.DEFAULT_MAX_ITEMS,
        )

    private val _settingsDraft = MutableStateFlow(WebDavConfig())
    val settingsDraft: StateFlow<WebDavConfig> = _settingsDraft.asStateFlow()

    private val _historyMaxDraft = MutableStateFlow(HistoryRepository.DEFAULT_MAX_ITEMS)
    val historyMaxDraft: StateFlow<Int> = _historyMaxDraft.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepo.configFlow.collect { saved ->
                _config.value = saved
                // 不在设置页编辑时，同步草稿
                if (_screen.value !is AppScreen.Settings) {
                    _settingsDraft.value = saved
                }
            }
        }
        viewModelScope.launch {
            historyRepo.maxItemsFlow.collect { max ->
                if (_screen.value !is AppScreen.Settings) {
                    _historyMaxDraft.value = max
                }
            }
        }
    }

    fun navigate(screen: AppScreen) {
        if (screen is AppScreen.Settings) {
            // 进入设置时用已落盘配置填充草稿
            _settingsDraft.value = _config.value
            _historyMaxDraft.value = historyMaxItems.value
            _settingsMessage.value = null
            _probeMessage.value = null
        }
        if (screen is AppScreen.History) {
            _historyMessage.value = null
        }
        _screen.value = screen
    }

    fun updateSettingsDraft(transform: (WebDavConfig) -> WebDavConfig) {
        _settingsDraft.value = transform(_settingsDraft.value)
    }

    fun updateHistoryMaxDraft(value: Int) {
        _historyMaxDraft.value = value.coerceIn(
            HistoryRepository.MIN_MAX_ITEMS,
            HistoryRepository.MAX_MAX_ITEMS,
        )
    }

    /** 连接配置 + 历史上限 一并落盘 */
    fun saveSettings() {
        viewModelScope.launch {
            val draft = _settingsDraft.value
            settingsRepo.save(draft)
            historyRepo.setMaxItems(_historyMaxDraft.value)
            _config.value = draft.copy(
                baseUrl = draft.baseUrl.trim(),
                remoteDir = draft.remoteDir.trim().trim('/'),
            )
            _settingsMessage.value = "设置已保存（含保活勾选）"
        }
    }

    fun probe() {
        viewModelScope.launch {
            _probeMessage.value = "正在测试连接..."
            // 先落盘再测，保证用的是当前输入
            settingsRepo.save(_settingsDraft.value)
            historyRepo.setMaxItems(_historyMaxDraft.value)
            _config.value = _settingsDraft.value
            val result = WebDavClient(_settingsDraft.value).probe()
            _probeMessage.value = result.fold(
                onSuccess = { it },
                onFailure = { "连接失败: ${it.message}" },
            )
            _settingsMessage.value = "连接配置已落盘"
        }
    }

    fun deleteHistory(id: String) {
        viewModelScope.launch {
            val ok = historyRepo.delete(id)
            _historyMessage.value = if (ok) "已删除" else "删除失败：记录不存在"
        }
    }

    fun deleteHistoryIds(ids: Collection<String>) {
        viewModelScope.launch {
            if (ids.isEmpty()) {
                _historyMessage.value = "未选择任何记录"
                return@launch
            }
            historyRepo.deleteAll(ids)
            _historyMessage.value = "已删除 ${ids.size} 条"
        }
    }

    fun refreshKeepAliveStatus() {
        _keepAliveStatus.value = KeepAliveHelper.status(getApplication())
    }

    fun clearHistory() {
        viewModelScope.launch {
            historyRepo.clear()
            _historyMessage.value = "历史已清空"
        }
    }
}