package com.webdav.uploader.data

enum class UploadHistoryStatus {
    SUCCESS,
    FAILED,
    CANCELLED,
}

data class UploadHistoryRecord(
    val id: String = java.util.UUID.randomUUID().toString(),
    val fileName: String,
    val fileSize: Long,
    val remotePath: String,
    val baseUrl: String,
    val status: UploadHistoryStatus,
    val message: String = "",
    val startedAt: Long,
    val finishedAt: Long,
    val durationMs: Long = (finishedAt - startedAt).coerceAtLeast(0),
)