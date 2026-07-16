package com.webdav.uploader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.webdav.uploader.data.SettingsRepository
import com.webdav.uploader.data.WebDavConfig
import com.webdav.uploader.webdav.WebDavClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SettingsRepository(app)

    private val _config = MutableStateFlow(WebDavConfig())
    val config: StateFlow<WebDavConfig> = _config.asStateFlow()

    private val _probeMessage = MutableStateFlow<String?>(null)
    val probeMessage: StateFlow<String?> = _probeMessage.asStateFlow()

    init {
        viewModelScope.launch {
            repo.configFlow.collect { saved ->
                // 仅在用户尚未本地编辑时同步；简化：始终以磁盘为准的初始加载
                if (!_dirty.value) {
                    _config.value = saved
                }
            }
        }
    }

    private val _dirty = MutableStateFlow(false)

    fun updateLocal(transform: (WebDavConfig) -> WebDavConfig) {
        _dirty.value = true
        _config.value = transform(_config.value)
    }

    fun save() {
        viewModelScope.launch {
            repo.save(_config.value)
            _dirty.value = false
            _probeMessage.value = "设置已保存"
        }
    }

    fun probe() {
        viewModelScope.launch {
            _probeMessage.value = "正在测试连接..."
            // 先保存再测，避免测的是旧配置
            repo.save(_config.value)
            _dirty.value = false
            val result = WebDavClient(_config.value).probe()
            _probeMessage.value = result.fold(
                onSuccess = { it },
                onFailure = { "连接失败: ${it.message}" },
            )
        }
    }
}

