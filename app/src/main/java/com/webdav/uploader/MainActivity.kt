package com.webdav.uploader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.webdav.uploader.ui.MainScreen
import com.webdav.uploader.ui.MainViewModel
import com.webdav.uploader.upload.UploadService

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeRequestNotificationPermission()
        handleShareIntent(intent)

        setContent {
            val config by vm.config.collectAsStateWithLifecycle()
            val upload by UploadService.state.collectAsStateWithLifecycle()
            val probeMsg by vm.probeMessage.collectAsStateWithLifecycle()
            val history by vm.history.collectAsStateWithLifecycle()
            val historyMax by vm.historyMaxItems.collectAsStateWithLifecycle()
            val historyMsg by vm.historyMessage.collectAsStateWithLifecycle()

            val pickFiles = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenMultipleDocuments()
            ) { uris: List<Uri> ->
                if (uris.isNotEmpty()) {
                    uris.forEach { uri ->
                        try {
                            contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION,
                            )
                        } catch (_: SecurityException) {
                            // 部分选择器不支持 persistable
                        }
                    }
                    UploadService.start(this, uris)
                }
            }

            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        config = config,
                        upload = upload,
                        probeMessage = probeMsg,
                        history = history,
                        historyMaxItems = historyMax,
                        historyMessage = historyMsg,
                        onConfigChange = vm::updateLocal,
                        onSave = vm::save,
                        onProbe = vm::probe,
                        onPickFiles = {
                            pickFiles.launch(arrayOf("*/*"))
                        },
                        onCancel = { UploadService.stop(this) },
                        onSetHistoryMaxItems = vm::setHistoryMaxItems,
                        onAddHistory = { fileName, fileSize, remotePath, status, message ->
                            vm.addHistory(fileName, fileSize, remotePath, status, message)
                        },
                        onUpdateHistory = vm::updateHistory,
                        onDeleteHistory = vm::deleteHistory,
                        onClearHistory = vm::clearHistory,
                    )
                }
            }
        }
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

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }
}