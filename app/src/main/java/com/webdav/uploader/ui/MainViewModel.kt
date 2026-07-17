package com.webdav.uploader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.webdav.uploader.data.SessionRepository
import com.webdav.uploader.data.SettingsRepository
import com.webdav.uploader.data.UploadSession
import com.webdav.uploader.data.UploadSessionSummary
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
    data object UploadRecords : AppScreen()
    data class UploadRecordDetail(val sessionId: String) : AppScreen()
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settingsRepo = SettingsRepository(app)
    private val sessionRepo = SessionRepository(app)

    private val _config = MutableStateFlow(WebDavConfig())
    val config: StateFlow<WebDavConfig> = _config.asStateFlow()

    val savedConfig: StateFlow<WebDavConfig> = settingsRepo.configFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WebDavConfig())

    private val _probeMessage = MutableStateFlow<String?>(null)
    val probeMessage: StateFlow<String?> = _probeMessage.asStateFlow()

    private val _settingsMessage = MutableStateFlow<String?>(null)
    val settingsMessage: StateFlow<String?> = _settingsMessage.asStateFlow()

    private val _keepAliveStatus = MutableStateFlow(
        KeepAliveStatus(
            batteryOptimizationIgnored = false,
            notificationGranted = false,
            canRequestBatteryOptimization = true,
        ),
    )
    val keepAliveStatus: StateFlow<KeepAliveStatus> = _keepAliveStatus.asStateFlow()

    private val _screen = MutableStateFlow<AppScreen>(AppScreen.Home)
    val screen: StateFlow<AppScreen> = _screen.asStateFlow()

    val sessions: StateFlow<List<UploadSessionSummary>> = sessionRepo.sessions

    /** 无需 root 的数据目录（Android/data/...） */
    val dataPath: String = sessionRepo.dataRootPath()

    private val _sessionDetail = MutableStateFlow<UploadSession?>(null)
    val sessionDetail: StateFlow<UploadSession?> = _sessionDetail.asStateFlow()

    private val _settingsDraft = MutableStateFlow(WebDavConfig())
    val settingsDraft: StateFlow<WebDavConfig> = _settingsDraft.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepo.configFlow.collect { saved ->
                _config.value = saved
                if (_screen.value !is AppScreen.Settings) {
                    _settingsDraft.value = saved
                }
            }
        }
        viewModelScope.launch {
            sessionRepo.refresh()
        }
    }

    fun navigate(screen: AppScreen) {
        when (screen) {
            is AppScreen.Settings -> {
                _settingsDraft.value = _config.value
                _settingsMessage.value = null
                _probeMessage.value = null
            }
            is AppScreen.UploadRecords -> {
                viewModelScope.launch { sessionRepo.refresh() }
            }
            is AppScreen.UploadRecordDetail -> {
                viewModelScope.launch {
                    _sessionDetail.value = sessionRepo.getSession(screen.sessionId)
                }
            }
            else -> Unit
        }
        _screen.value = screen
    }

    fun updateSettingsDraft(transform: (WebDavConfig) -> WebDavConfig) {
        _settingsDraft.value = transform(_settingsDraft.value)
    }

    fun saveSettings() {
        viewModelScope.launch {
            val draft = _settingsDraft.value
            settingsRepo.save(draft)
            _config.value = draft.copy(
                baseUrl = draft.baseUrl.trim(),
                remoteDir = draft.remoteDir.trim().trim('/'),
            )
            _settingsMessage.value = "设置已保存"
        }
    }

    fun probe() {
        viewModelScope.launch {
            _probeMessage.value = "正在测试连接..."
            settingsRepo.save(_settingsDraft.value)
            _config.value = _settingsDraft.value
            val result = WebDavClient(_settingsDraft.value).probe()
            _probeMessage.value = result.fold(
                onSuccess = { it },
                onFailure = { "连接失败: ${it.message}" },
            )
            _settingsMessage.value = "连接配置已保存"
        }
    }

    fun deleteRecordsInSession(sessionId: String, recordIds: Collection<String>) {
        viewModelScope.launch {
            val updated = sessionRepo.deleteRecords(sessionId, recordIds)
            _sessionDetail.value = updated
        }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            sessionRepo.deleteSession(id)
            if ((_screen.value as? AppScreen.UploadRecordDetail)?.sessionId == id) {
                navigate(AppScreen.UploadRecords)
            } else {
                sessionRepo.refresh()
            }
        }
    }

    fun clearSessions() {
        viewModelScope.launch {
            sessionRepo.clearAllSessions()
            _sessionDetail.value = null
        }
    }

    fun refreshKeepAliveStatus() {
        _keepAliveStatus.value = KeepAliveHelper.status(getApplication())
    }
}