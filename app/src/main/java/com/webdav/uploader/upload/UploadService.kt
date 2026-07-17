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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val queueMutex = Mutex()
    private var wakeLock: PowerManager.WakeLock? = null
    private var useForeground = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        instance = this
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundCompat()
                scope.launch {
                    val config = SettingsRepository(applicationContext).configFlow.first()
                    useForeground = config.keepAliveForegroundNotification
                    if (!useForeground) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    }
                    acquireWakeLock()
                    // 优先从进程内队列取完整列表；兼容旧 Intent EXTRA
                    val queued = PendingUploadQueue.take()
                    val fromIntent = intent.getStringArrayListExtra(EXTRA_URIS)
                        ?.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
                        .orEmpty()
                    val uris = if (queued.isNotEmpty()) queued else fromIntent
                    enqueue(uris, config)
                }
            }
            ACTION_STOP -> {
                job?.cancel()
                _state.value = _state.value.copy(
                    running = false,
                    phase = "已取消",
                    log = appendLog(_state.value.log, "用户取消上传"),
                )
                releaseWakeLock()
                if (useForeground) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun enqueue(uris: List<Uri>, preloadedConfig: WebDavConfig? = null) {
        job?.cancel()
        job = scope.launch {
            queueMutex.withLock {
                val settings = SettingsRepository(applicationContext)
                                val sessionRepo = SessionRepository(applicationContext)
                val config = preloadedConfig ?: settings.configFlow.first()
                useForeground = config.keepAliveForegroundNotification

                val totalCount = uris.size
                if (totalCount == 0) {
                    _state.value = UploadUiState(
                        running = false,
                        phase = "无有效文件",
                        lastError = "没有可上传的文件",
                    )
                    finishService()
                    return@withLock
                }

                val remoteDir = config.remoteDir.trim('/')
                val sessionId = sessionRepo.startSession(
                    baseUrl = config.baseUrl,
                    remoteDir = remoteDir,
                )

                val client = WebDavClient(config)
                var successCount = 0
                var failedCount = 0
                var cancelledCount = 0
                var log = buildString {
                    appendLine("开始上传 $totalCount 个文件")
                    appendLine("远端目录: /$remoteDir")
                    appendLine("读超时: ${config.readTimeoutSec}s")
                    appendLine("任务ID: $sessionId")
                    appendLine("完整结果写入「上传记录」")
                    if (totalCount >= 500) {
                        appendLine("大批量：进程内队列 + 串行上传")
                    }
                }

                _state.value = UploadUiState(
                    running = true,
                    totalCount = totalCount,
                    phase = "准备中",
                    log = log,
                    sessionId = sessionId,
                )

                client.ensureDir(remoteDir).onFailure { e ->
                    log = appendLog(log, "创建目录警告: ${e.message}")
                    _state.value = _state.value.copy(log = log)
                }

                uris.forEachIndexed { index, uri ->
                    val item = resolveItem(uri)
                    if (item == null) {
                        val finishedAt = System.currentTimeMillis()
                        failedCount++
                        val record = UploadHistoryRecord(
                            fileName = uri.lastPathSegment ?: "unknown",
                            fileSize = 0,
                            remotePath = "",
                            baseUrl = config.baseUrl,
                            status = UploadHistoryStatus.FAILED,
                            message = "无法读取文件",
                            startedAt = finishedAt,
                            finishedAt = finishedAt,
                        )
                        // 批次完整记录
                        sessionRepo.appendRecord(sessionId, record)
                        // 全局历史仍写（可能被上限截断）
                        log = appendLog(log, "跳过无法读取: ${record.fileName}")
                        _state.value = _state.value.copy(
                            log = log,
                            failedCount = failedCount,
                            lastError = "无法读取文件",
                        )
                        return@forEachIndexed
                    }

                    val remotePath = if (remoteDir.isBlank()) item.displayName
                    else "$remoteDir/${item.displayName}"
                    val startedAt = System.currentTimeMillis()

                    log = appendLog(log, "[${index + 1}/$totalCount] ${item.displayName}")
                    if (log.length > 12_000) log = log.takeLast(8_000)

                    _state.value = _state.value.copy(
                        running = true,
                        currentName = item.displayName,
                        currentIndex = index + 1,
                        totalCount = totalCount,
                        bytesSent = 0,
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
                            "发送 ${item.displayName} (${index + 1}/$totalCount)",
                            0,
                            item.size,
                        )
                    }

                    val contentType = contentResolver.getType(uri)
                    try {
                        val result = contentResolver.openInputStream(uri).use { input ->
                            if (input == null) Result.failure(IllegalStateException("无法打开文件流"))
                            else client.upload(
                                remotePath = remotePath,
                                contentLength = item.size,
                                contentType = contentType,
                                input = input,
                            ) { progress ->
                                val shouldUpdateUi =
                                    progress.bytesSent == progress.totalBytes ||
                                        progress.bytesSent % (256 * 1024) < 64 * 1024
                                if (shouldUpdateUi) {
                                    _state.value = _state.value.copy(
                                        bytesSent = progress.bytesSent,
                                        totalBytes = progress.totalBytes,
                                        phase = if (progress.bytesSent >= progress.totalBytes && progress.totalBytes > 0) {
                                            "等待服务端完成(crypt/网盘)..."
                                        } else {
                                            "发送数据"
                                        },
                                    )
                                    if (useForeground) {
                                        updateNotification(
                                            title = if (progress.bytesSent >= progress.totalBytes && progress.totalBytes > 0) {
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
                        }

                        val finishedAt = System.currentTimeMillis()
                        result.onSuccess {
                            successCount++
                            log = appendLog(log, "成功: ${item.displayName}")
                            val record = UploadHistoryRecord(
                                fileName = item.displayName,
                                fileSize = item.size,
                                remotePath = remotePath,
                                baseUrl = config.baseUrl,
                                status = UploadHistoryStatus.SUCCESS,
                                message = "上传成功",
                                startedAt = startedAt,
                                finishedAt = finishedAt,
                            )
                            sessionRepo.appendRecord(sessionId, record)
                            _state.value = _state.value.copy(
                                phase = "成功",
                                log = log,
                                successCount = successCount,
                            )
                        }.onFailure { e ->
                            failedCount++
                            log = appendLog(log, "失败: ${item.displayName} -> ${e.message}")
                            val record = UploadHistoryRecord(
                                fileName = item.displayName,
                                fileSize = item.size,
                                remotePath = remotePath,
                                baseUrl = config.baseUrl,
                                status = UploadHistoryStatus.FAILED,
                                message = e.message.orEmpty().ifBlank { "上传失败" },
                                startedAt = startedAt,
                                finishedAt = finishedAt,
                            )
                            sessionRepo.appendRecord(sessionId, record)
                            _state.value = _state.value.copy(
                                phase = "失败",
                                log = log,
                                lastError = e.message,
                                failedCount = failedCount,
                            )
                        }
                    } catch (e: CancellationException) {
                        cancelledCount++
                        val finishedAt = System.currentTimeMillis()
                        log = appendLog(log, "取消: ${item.displayName}")
                        val record = UploadHistoryRecord(
                            fileName = item.displayName,
                            fileSize = item.size,
                            remotePath = remotePath,
                            baseUrl = config.baseUrl,
                            status = UploadHistoryStatus.CANCELLED,
                            message = "用户取消",
                            startedAt = startedAt,
                            finishedAt = finishedAt,
                        )
                        sessionRepo.appendRecord(sessionId, record)
                        sessionRepo.finishSession(sessionId)
                        throw e
                    }
                }

                sessionRepo.finishSession(sessionId)
                log = appendLog(
                    log,
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
                        "上传结束 成功$successCount 失败$failedCount",
                        1,
                        1,
                        ongoing = false,
                    )
                }
                finishService()
            }
        }
    }

    private fun resolveItem(uri: Uri): UploadItem? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                val name = if (nameIdx >= 0) cursor.getString(nameIdx) else "file"
                val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else -1L
                val finalSize = if (size >= 0) size else {
                    contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                }
                if (finalSize < 0) return null
                return UploadItem(uri.toString(), name ?: "file", finalSize)
            }
        }
        return null
    }

    private fun startForegroundCompat() {
        val notification = buildNotification("准备上传", 0, 0, ongoing = true)
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

        if (totalBytes > 0) {
            val percent = ((bytesSent * 100) / totalBytes).toInt().coerceIn(0, 100)
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
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "webdavuploader:upload").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    private fun finishService() {
        releaseWakeLock()
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

        /**
         * 大批量推荐路径：Uri 放进程内队列，Intent 只负责启动服务。
         * 不再依赖 ClipData 前 500 限制。
         */
        fun start(context: Context, uris: List<Uri>) {
            if (uris.isEmpty()) return
            PendingUploadQueue.set(uris)
            val intent = Intent(context, UploadService::class.java).apply {
                action = ACTION_START
                // 小批量仍附带 EXTRA 作为兜底；大批量以队列为准
                if (uris.size <= 200) {
                    putStringArrayListExtra(EXTRA_URIS, ArrayList(uris.map { it.toString() }))
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ContextCompat.startForegroundService(context, intent)
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
    val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        .format(java.util.Date())
    return (old + "[$ts] $line\n").takeLast(8000)
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