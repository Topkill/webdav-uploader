package com.webdav.uploader.upload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.webdav.uploader.MainActivity
import com.webdav.uploader.R
import com.webdav.uploader.data.SessionRepository
import com.webdav.uploader.data.SettingsRepository
import com.webdav.uploader.data.UploadHistoryRecord
import com.webdav.uploader.data.UploadHistoryStatus
import com.webdav.uploader.data.WebDavConfig
import com.webdav.uploader.webdav.WebDavClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

data class UploadItem(
    val uri: String,
    val displayName: String,
    val size: Long,
)

data class UploadUiState(
    val running: Boolean = false,
    val currentName: String = "",
    val currentIndex: Int = 0,
    val totalCount: Int = 0,
    val bytesSent: Long = 0,
    val totalBytes: Long = 0,
    val phase: String = "空闲",
    val log: String = "",
    val lastError: String? = null,
    val sessionId: String? = null,
    val successCount: Int = 0,
    val failedCount: Int = 0,
    val cancelledCount: Int = 0,
)

class UploadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var useForeground = true
    private var processLogSessionId: String? = null
    private var processLogRepo: SessionRepository? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        instance = this
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val extraUris = intent?.getStringArrayListExtra(EXTRA_URIS)
        when (action) {
            ACTION_START -> {
                try {
                    startForegroundCompat()
                } catch (e: Exception) {
                    _state.value = _state.value.copy(
                        lastError = "前台服务启动失败: ${e.message}",
                        log = appendLog(_state.value.log, "前台服务启动失败: ${e.message}"),
                    )
                }
                scope.launch {
                    try {
                        val config = SettingsRepository(applicationContext).configFlow.first()
                        useForeground = config.keepAliveForegroundNotification
                        if (!useForeground) {
                            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                        }
                        acquireWakeLock()
                        val queued = PendingUploadQueue.take()
                        val fromIntent = extraUris
                            ?.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
                            .orEmpty()
                        val uris = if (queued.isNotEmpty()) queued else fromIntent
                        if (uris.isEmpty()) {
                            _state.value = _state.value.copy(
                                running = false,
                                phase = "无有效文件",
                                lastError = "没有可上传的文件",
                            )
                            finishService()
                            return@launch
                        }
                        runUpload(uris, config)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val msg = e.message ?: e.javaClass.simpleName
                        _state.value = _state.value.copy(
                            running = false,
                            phase = "启动失败",
                            lastError = msg,
                            log = appendLog(_state.value.log, "启动失败: $msg"),
                        )
                        finishService()
                    }
                }
            }
            ACTION_STOP -> {
                job?.cancel()
                val cancelLine = "用户取消上传"
                _state.value = _state.value.copy(
                    running = false,
                    phase = "已取消",
                    log = appendLog(_state.value.log, cancelLine),
                )
                val sid = processLogSessionId
                val repo = processLogRepo
                if (sid != null && repo != null) {
                    scope.launch {
                        runCatching { repo.appendProcessLog(sid, cancelLine) }
                    }
                }
                releaseWakeLock()
                if (useForeground) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun runUpload(uris: List<Uri>, config: WebDavConfig) {
        job?.cancel()
        job = scope.launch {
            var log = ""
            try {
                val sessionRepo = SessionRepository(applicationContext)
                useForeground = config.keepAliveForegroundNotification
                val totalCount = uris.size
                if (totalCount == 0) {
                    _state.value = UploadUiState(
                        running = false,
                        phase = "无有效文件",
                        lastError = "没有可上传的文件",
                    )
                    finishService()
                    return@launch
                }

                val remoteDir = config.remoteDir.trim('/')
                val sessionId = sessionRepo.startSession(
                    baseUrl = config.baseUrl,
                    remoteDir = remoteDir,
                )
                processLogSessionId = sessionId
                processLogRepo = sessionRepo

                suspend fun note(line: String) {
                    runCatching { sessionRepo.appendProcessLog(sessionId, line) }
                    log = appendLog(log, line)
                }

                note("开始上传 $totalCount 个文件")
                note("远端目录: /$remoteDir")
                note("读超时: ${config.readTimeoutSec}s")
                note("任务ID: $sessionId")
                note("完整结果写入「上传记录」")
                if (totalCount >= 500) {
                    note("大批量：进程内队列 + 串行上传")
                }

                _state.value = UploadUiState(
                    running = true,
                    totalCount = totalCount,
                    phase = "准备中",
                    log = log,
                    sessionId = sessionId,
                )

                val client = WebDavClient(config)
                val ensureErr = client.ensureDir(remoteDir).exceptionOrNull()
                if (ensureErr != null) {
                    note("创建目录警告: ${ensureErr.message}")
                    _state.value = _state.value.copy(log = log)
                }

                var successCount = 0
                var failedCount = 0
                var cancelledCount = 0

                for ((index, uri) in uris.withIndex()) {
                    val item = resolveItem(uri)
                    if (item == null) {
                        val finishedAt = System.currentTimeMillis()
                        failedCount += 1
                        val record = UploadHistoryRecord(
                            fileName = uri.lastPathSegment ?: "unknown",
                            fileSize = 0L,
                            remotePath = "",
                            baseUrl = config.baseUrl,
                            status = UploadHistoryStatus.FAILED,
                            message = "无法读取文件",
                            startedAt = finishedAt,
                            finishedAt = finishedAt,
                        )
                        sessionRepo.appendRecord(sessionId, record)
                        note("跳过无法读取: ${record.fileName}")
                        _state.value = _state.value.copy(
                            log = log,
                            failedCount = failedCount,
                            lastError = "无法读取文件",
                        )
                        continue
                    }

                    val remotePath = if (remoteDir.isBlank()) {
                        item.displayName
                    } else {
                        "$remoteDir/${item.displayName}"
                    }
                    val startedAt = System.currentTimeMillis()
                    note("[${index + 1}/$totalCount] ${item.displayName}")
                    if (log.length > 12000) {
                        log = log.takeLast(8000)
                    }

                    _state.value = _state.value.copy(
                        running = true,
                        currentName = item.displayName,
                        currentIndex = index + 1,
                        totalCount = totalCount,
                        bytesSent = 0L,
                        totalBytes = item.size,
                        phase = "发送数据",
                        log = log,
                        lastError = null,
                        sessionId = sessionId,
                        successCount = successCount,
                        failedCount = failedCount,
                        cancelledCount = cancelledCount,
                    )
                    if (useForeground) {
                        updateNotification(
                            title = "发送 ${item.displayName} (${index + 1}/$totalCount)",
                            bytesSent = 0L,
                            totalBytes = item.size,
                        )
                    }

                    val contentType = contentResolver.getType(uri)
                    try {
                        val inputStream = contentResolver.openInputStream(uri)
                        val result: Result<Unit> = if (inputStream == null) {
                            Result.failure(IllegalStateException("无法打开文件流"))
                        } else {
                            try {
                                client.upload(
                                    remotePath = remotePath,
                                    contentLength = item.size,
                                    contentType = contentType,
                                    input = inputStream,
                                ) { progress ->
                                    val waiting =
                                        progress.bytesSent >= progress.totalBytes &&
                                            progress.totalBytes > 0L
                                    val shouldUpdateUi =
                                        waiting ||
                                            progress.bytesSent % (256L * 1024L) < 64L * 1024L
                                    if (shouldUpdateUi) {
                                        _state.value = _state.value.copy(
                                            bytesSent = progress.bytesSent,
                                            totalBytes = progress.totalBytes,
                                            phase = if (waiting) {
                                                "等待服务端完成(crypt/网盘)..."
                                            } else {
                                                "发送数据"
                                            },
                                        )
                                        if (useForeground) {
                                            updateNotification(
                                                title = if (waiting) {
                                                    "等待完成: ${item.displayName}"
                                                } else {
                                                    "上传: ${item.displayName} (${index + 1}/$totalCount)"
                                                },
                                                bytesSent = progress.bytesSent,
                                                totalBytes = progress.totalBytes,
                                            )
                                        }
                                    }
                                }
                            } finally {
                                runCatching { inputStream.close() }
                            }
                        }

                        val finishedAt = System.currentTimeMillis()
                        val err = result.exceptionOrNull()
                        if (err == null) {
                            successCount += 1
                            note("成功: ${item.displayName}")
                            sessionRepo.appendRecord(
                                sessionId,
                                UploadHistoryRecord(
                                    fileName = item.displayName,
                                    fileSize = item.size,
                                    remotePath = remotePath,
                                    baseUrl = config.baseUrl,
                                    status = UploadHistoryStatus.SUCCESS,
                                    message = "上传成功",
                                    startedAt = startedAt,
                                    finishedAt = finishedAt,
                                ),
                            )
                            _state.value = _state.value.copy(
                                phase = "成功",
                                log = log,
                                successCount = successCount,
                            )
                        } else {
                            failedCount += 1
                            note("失败: ${item.displayName} -> ${err.message}")
                            sessionRepo.appendRecord(
                                sessionId,
                                UploadHistoryRecord(
                                    fileName = item.displayName,
                                    fileSize = item.size,
                                    remotePath = remotePath,
                                    baseUrl = config.baseUrl,
                                    status = UploadHistoryStatus.FAILED,
                                    message = err.message.orEmpty().ifBlank { "上传失败" },
                                    startedAt = startedAt,
                                    finishedAt = finishedAt,
                                ),
                            )
                            _state.value = _state.value.copy(
                                phase = "失败",
                                log = log,
                                lastError = err.message,
                                failedCount = failedCount,
                            )
                        }
                    } catch (e: CancellationException) {
                        cancelledCount += 1
                        val finishedAt = System.currentTimeMillis()
                        note("取消: ${item.displayName}")
                        sessionRepo.appendRecord(
                            sessionId,
                            UploadHistoryRecord(
                                fileName = item.displayName,
                                fileSize = item.size,
                                remotePath = remotePath,
                                baseUrl = config.baseUrl,
                                status = UploadHistoryStatus.CANCELLED,
                                message = "用户取消",
                                startedAt = startedAt,
                                finishedAt = finishedAt,
                            ),
                        )
                        sessionRepo.finishSession(sessionId)
                        throw e
                    }
                }

                sessionRepo.finishSession(sessionId)
                note(
                    "全部结束：成功 $successCount / 失败 $failedCount / 取消 $cancelledCount（共 $totalCount）",
                )
                _state.value = _state.value.copy(
                    running = false,
                    phase = "完成",
                    log = log,
                    currentName = "",
                    sessionId = sessionId,
                    successCount = successCount,
                    failedCount = failedCount,
                    cancelledCount = cancelledCount,
                )
                if (useForeground) {
                    updateNotification(
                        title = "上传结束 成功$successCount 失败$failedCount",
                        bytesSent = 1L,
                        totalBytes = 1L,
                        ongoing = false,
                    )
                }
                finishService()
            } catch (e: CancellationException) {
                _state.value = _state.value.copy(
                    running = false,
                    phase = "已取消",
                    lastError = "用户取消",
                    log = log,
                )
                finishService()
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                log = appendLog(log, "致命错误: $msg")
                _state.value = _state.value.copy(
                    running = false,
                    phase = "异常退出",
                    lastError = msg,
                    log = log,
                )
                val sid = processLogSessionId
                val repo = processLogRepo
                if (sid != null && repo != null) {
                    runCatching { repo.appendProcessLog(sid, "致命错误: $msg") }
                }
                if (useForeground) {
                    runCatching {
                        updateNotification(
                            title = "上传异常: $msg",
                            bytesSent = 0L,
                            totalBytes = 0L,
                            ongoing = false,
                        )
                    }
                }
                finishService()
            }
        }
    }

    private fun resolveItem(uri: Uri): UploadItem? {
        return try {
            var resolved: UploadItem? = null
            val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) else "file"
                    var size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else -1L
                    if (size < 0L) {
                        size = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                    }
                    if (size >= 0L) {
                        resolved = UploadItem(
                            uri = uri.toString(),
                            displayName = name ?: "file",
                            size = size,
                        )
                    }
                }
            }
            if (resolved != null) {
                return resolved
            }
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                val size = afd.length
                if (size >= 0L) {
                    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
                    resolved = UploadItem(uri.toString(), name, size)
                }
            }
            resolved
        } catch (ignored: Exception) {
            null
        }
    }

    private fun startForegroundCompat() {
        val notification = buildNotification("准备上传", 0L, 0L, ongoing = true)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(
        title: String,
        bytesSent: Long,
        totalBytes: Long,
        ongoing: Boolean = true,
    ) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(title, bytesSent, totalBytes, ongoing))
    }

    private fun buildNotification(
        title: String,
        bytesSent: Long,
        totalBytes: Long,
        ongoing: Boolean,
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, UploadService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .addAction(0, "取消", stopIntent)
        if (totalBytes > 0L) {
            val percent = ((bytesSent * 100L) / totalBytes).toInt().coerceIn(0, 100)
            builder.setContentText("${formatSize(bytesSent)} / ${formatSize(totalBytes)} ($percent%)")
            builder.setProgress(100, percent, false)
        } else {
            builder.setContentText("进行中")
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.upload_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.upload_channel_desc)
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) {
            return
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "webdavuploader:upload").apply {
            setReferenceCounted(false)
            acquire(6L * 60L * 60L * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null
    }

    private fun finishService() {
        releaseWakeLock()
        processLogSessionId = null
        processLogRepo = null
        if (useForeground) {
            stopForeground(STOP_FOREGROUND_DETACH)
        }
        stopSelf()
    }

    companion object {
        const val ACTION_START = "com.webdav.uploader.START"
        const val ACTION_STOP = "com.webdav.uploader.STOP"
        const val EXTRA_URIS = "uris"
        private const val CHANNEL_ID = "webdav_upload"
        private const val NOTIFICATION_ID = 42

        private val _state = MutableStateFlow(UploadUiState())
        val state: StateFlow<UploadUiState> = _state.asStateFlow()

        @Volatile
        private var instance: UploadService? = null

        fun start(context: Context, uris: List<Uri>) {
            if (uris.isEmpty()) {
                return
            }
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
            PendingUploadQueue.set(uris)
            val intent = Intent(context, UploadService::class.java).apply {
                action = ACTION_START
                if (uris.size <= 200) {
                    putStringArrayListExtra(EXTRA_URIS, ArrayList(uris.map { it.toString() }))
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                runCatching { context.startService(intent) }
                _state.value = _state.value.copy(
                    lastError = "启动上传服务失败: ${e.message}",
                    log = appendLog(_state.value.log, "启动上传服务失败: ${e.message}"),
                )
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, UploadService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}

private fun appendLog(old: String, line: String): String {
    val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    return (old + "[$ts] $line\n").takeLast(8000)
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024L) {
        return "$bytes B"
    }
    val kb = bytes / 1024.0
    if (kb < 1024.0) {
        return String.format(Locale.US, "%.1f KB", kb)
    }
    val mb = kb / 1024.0
    if (mb < 1024.0) {
        return String.format(Locale.US, "%.1f MB", mb)
    }
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}
