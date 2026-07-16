package com.webdav.uploader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.webdav.uploader.keepalive.KeepAliveHelper
import com.webdav.uploader.ui.AppScreen
import com.webdav.uploader.ui.HistoryScreen
import com.webdav.uploader.ui.MainScreen
import com.webdav.uploader.ui.MainViewModel
import com.webdav.uploader.ui.SettingsScreen
import com.webdav.uploader.upload.UploadService

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            vm.refreshKeepAliveStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeRequestNotificationPermission()
        handleShareIntent(intent)
        vm.refreshKeepAliveStatus()

        setContent {
            val screen by vm.screen.collectAsStateWithLifecycle()
            val config by vm.config.collectAsStateWithLifecycle()
            val upload by UploadService.state.collectAsStateWithLifecycle()
            val settingsDraft by vm.settingsDraft.collectAsStateWithLifecycle()
            val historyMaxDraft by vm.historyMaxDraft.collectAsStateWithLifecycle()
            val settingsMessage by vm.settingsMessage.collectAsStateWithLifecycle()
            val probeMsg by vm.probeMessage.collectAsStateWithLifecycle()
            val history by vm.history.collectAsStateWithLifecycle()
            val historyMax by vm.historyMaxItems.collectAsStateWithLifecycle()
            val historyMsg by vm.historyMessage.collectAsStateWithLifecycle()
            val keepAliveStatus by vm.keepAliveStatus.collectAsStateWithLifecycle()

            val pickFiles = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenMultipleDocuments(),
            ) { uris: List<Uri> ->
                if (uris.isNotEmpty()) {
                    // 大量文件时不要 takePersistableUriPermission，会显著增加内存与系统开销
                    // 上传期间通过 Intent FLAG + ClipData 传递读权限即可
                    UploadService.start(this, uris)
                }
            }

            BackHandler(enabled = screen !is AppScreen.Home) {
                vm.navigate(AppScreen.Home)
            }

            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (screen) {
                        AppScreen.Home -> MainScreen(
                            config = config,
                            upload = upload,
                            onPickFiles = { pickFiles.launch(arrayOf("*/*")) },
                            onCancel = { UploadService.stop(this) },
                            onOpenSettings = { vm.navigate(AppScreen.Settings) },
                            onOpenHistory = { vm.navigate(AppScreen.History) },
                        )
                        AppScreen.Settings -> SettingsScreen(
                            draft = settingsDraft,
                            historyMaxDraft = historyMaxDraft,
                            settingsMessage = settingsMessage,
                            probeMessage = probeMsg,
                            keepAliveStatus = keepAliveStatus,
                            onDraftChange = vm::updateSettingsDraft,
                            onHistoryMaxChange = vm::updateHistoryMaxDraft,
                            onSave = vm::saveSettings,
                            onProbe = vm::probe,
                            onApplyKeepAlive = {
                                // 先保存勾选，再按勾选项申请系统权限/跳转
                                vm.saveSettings()
                                KeepAliveHelper.applyEnabledOptions(this, vm.settingsDraft.value)
                                if (vm.settingsDraft.value.keepAliveForegroundNotification) {
                                    maybeRequestNotificationPermission(force = true)
                                }
                                vm.refreshKeepAliveStatus()
                            },
                            onRequestNotificationPermission = {
                                maybeRequestNotificationPermission(force = true)
                            },
                            onBack = { vm.navigate(AppScreen.Home) },
                        )
                        AppScreen.History -> HistoryScreen(
                            history = history,
                            historyMaxItems = historyMax,
                            historyMessage = historyMsg,
                            onDelete = vm::deleteHistory,
                            onDeleteIds = vm::deleteHistoryIds,
                            onClear = vm::clearHistory,
                            onBack = { vm.navigate(AppScreen.Home) },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshKeepAliveStatus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (uri != null) UploadService.start(this, listOf(uri))
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                if (!uris.isNullOrEmpty()) UploadService.start(this, uris.filterNotNull())
            }
        }
    }

    private fun maybeRequestNotificationPermission(force: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted && (force || true)) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        vm.refreshKeepAliveStatus()
    }
}