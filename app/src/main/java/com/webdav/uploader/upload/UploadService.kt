package com.webdav.uploader.upload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
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
import com.webdav.uploader.data.HistoryRepository
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
                val uris = intent.getStringArrayListExtra(EXTRA_URIS).orEmpty()
                if (uris.isNotEmpty()) {
                    // startForegroundService 要求尽快进入前台，否则 ANR
                    startForegroundCompat()
                    scope.launch {
                        val config = SettingsRepository(applicationContext).configFlow.first()
                        useForeground = config.keepAliveForegroundNotification
                        if (!useForeground) {
                            // 用户关闭前台保活：满足系统要求后立即摘掉前台
                            stopForeground(STOP_FOREGROUND_REMOVE)
                        }
                        acquireWakeLock()
                        enqueue(uris.map { Uri.parse(it) }, config)
                    }
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
                val historyRepo = HistoryRepository(applicationContext)
                val config = preloadedConfig ?: settings.configFlow.first()
                useForeground = config.keepAliveForegroundNotification

                // 注意：只保存 Uri 字符串列表，串行处理；不要把所有文件内容读进内存
                val totalCount = uris.size
                if (totalCount == 0) {
                    _state.value = UploadUiState(
                        running = false,
                        phase = "无有效文件",
                        lastError = "无法读取所选文件",
                    )
                    finishService()
                    return@withLock
                }

                val client = WebDavClient(config)
                val remoteDir = config.remoteDir.trim('/')
                var log = "开始上传 $totalCount 个文件\n远端目录: /$remoteDir\n读超时: ${config.readTimeoutSec}s\n"
                if (totalCount >= 500) {
                    log = appendLog(log, "大批量模式：串行上传，逐个解析，避免一次吃爆内存")
                }

                _state.value = UploadUiState(
                    running = true,
                    totalCount = totalCount,
                    phase = "准备中",
                    log = log,
                )

                client.ensureDir(remoteDir).onFailure { e ->
                    log = appendLog(log, "创建目录警告: ${e.message}")
                    _state.value = _state.value.copy(log = log)
                }

                uris.forEachIndexed { index, uri ->
                    // 逐个解析 metadata，避免几千文件时一次性 resolve 占内存
                    val item = resolveItem(uri)
                    if (item == null) {
                        log = appendLog(log, "跳过无法读取的文件: $uri")
                        _state.value = _state.value.copy(log = log)
                        return@forEachIndexed
                    }

                    val remotePath = if (remoteDir.isBlank()) item.displayName
                    else "$remoteDir/${item.displayName}"
                    val startedAt = System.currentTimeMillis()

                    log = appendLog(log, "[${index + 1}/$totalCount] 上传 ${item.displayName}")
                    // 日志截断，防止几千文件把 log 字符串撑爆
                    if (log.length > 12_000) {
                        log = log.takeLast(8_000)
                    }

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
                    )
                    if (useForeground) {
                        updateNotification("发送 ${item.displayName}", 0, item.size)
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
                                // 降低通知刷新频率：每 256KB 或完成时更新
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
                                                "上传: ${item.displayName}"
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
                            log = appendLog(log, "成功: ${item.displayName}")
                            _state.value = _state.value.copy(phase = "成功", log = log)
                            historyRepo.add(
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
                        }.onFailure { e ->
                            log = appendLog(log, "失败: ${item.displayName} -> ${e.message}")
                            _state.value = _state.value.copy(
                                phase = "失败",
                                log = log,
                                lastError = e.message,
                            )
                            historyRepo.add(
                                UploadHistoryRecord(
                                    fileName = item.displayName,
                                    fileSize = item.size,
                                    remotePath = remotePath,
                                    baseUrl = config.baseUrl,
                                    status = UploadHistoryStatus.FAILED,
                                    message = e.message.orEmpty().ifBlank { "上传失败" },
                                    startedAt = startedAt,
                                    finishedAt = finishedAt,
                                ),
                            )
                        }
                    } catch (e: CancellationException) {
                        val finishedAt = System.currentTimeMillis()
                        log = appendLog(log, "取消: ${item.displayName}")
                        historyRepo.add(
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
                        throw e
                    }
                }

                log = appendLog(log, "全部任务结束")
                _state.value = _state.value.copy(
                    running = false,
                    phase = "完成",
                    log = log,
                    currentName = "",
                )
                if (useForeground) {
                    updateNotification("上传结束", 1, 1, ongoing = false)
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
            acquire(6 * 60 * 60 * 1000L) // 最长 6 小时，防止异常泄漏
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
        /** 单次 Intent 可携带的 Uri 上限，防止 Binder/ClipData 爆内存 */
        private const val MAX_URIS_PER_BATCH = 2000

        private val _state = MutableStateFlow(UploadUiState())
        val state: StateFlow<UploadUiState> = _state.asStateFlow()

        @Volatile
        private var instance: UploadService? = null

        fun start(context: Context, uris: List<Uri>) {
            if (uris.isEmpty()) return
            val limited = if (uris.size > MAX_URIS_PER_BATCH) {
                uris.take(MAX_URIS_PER_BATCH)
            } else {
                uris
            }

            val intent = Intent(context, UploadService::class.java).apply {
                action = ACTION_START
                putStringArrayListExtra(EXTRA_URIS, ArrayList(limited.map { it.toString() }))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // ClipData 只挂前若干个 URI 以授予读权限；过多时依赖调用方仍在前台期间可读
                val grantCount = limited.size.coerceAtMost(500)
                if (grantCount > 0) {
                    val first = limited.first()
                    clipData = ClipData.newUri(context.contentResolver, "upload", first)
                    for (i in 1 until grantCount) {
                        clipData?.addItem(ClipData.Item(limited[i]))
                    }
                }
            }
            // 始终用 foreground service 启动更稳；是否显示前台通知由服务内配置决定
            // Android 8+ 要求 startForegroundService 后尽快 startForeground
            // 若用户关闭“前台通知保活”，我们仍会短暂 startForeground 满足系统要求后可降级
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