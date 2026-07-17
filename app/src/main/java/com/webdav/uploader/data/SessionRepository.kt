package com.webdav.uploader.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 一次上传任务的摘要 */
data class UploadSessionSummary(
    val id: String,
    val startedAt: Long,
    val finishedAt: Long,
    val total: Int,
    val success: Int,
    val failed: Int,
    val cancelled: Int,
    val remoteDir: String,
    val baseUrl: String,
)

/** 一次上传任务的完整结果（无条数/批次数上限，用户可删） */
data class UploadSession(
    val summary: UploadSessionSummary,
    val records: List<UploadHistoryRecord>,
)

class SessionRepository(context: Context) {
    private val dir = File(context.filesDir, "sessions").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val mutex = Mutex()

    private val _sessions = MutableStateFlow<List<UploadSessionSummary>>(emptyList())
    val sessions: StateFlow<List<UploadSessionSummary>> = _sessions.asStateFlow()

    init {
        _sessions.value = readIndex()
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutex.withLock { _sessions.value = readIndex() }
    }

    suspend fun startSession(
        baseUrl: String,
        remoteDir: String,
    ): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val summary = UploadSessionSummary(
                id = id,
                startedAt = now,
                finishedAt = 0L,
                total = 0,
                success = 0,
                failed = 0,
                cancelled = 0,
                remoteDir = remoteDir,
                baseUrl = baseUrl,
            )
            writeSession(UploadSession(summary, emptyList()))
            // 新任务插到最前，不截断
            val index = listOf(summary) + readIndex().filterNot { it.id == id }
            writeIndex(index)
            _sessions.value = index
            id
        }
    }

    suspend fun appendRecord(sessionId: String, record: UploadHistoryRecord) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = readSession(sessionId) ?: return@withLock
                val records = current.records + record
                val success = records.count { it.status == UploadHistoryStatus.SUCCESS }
                val failed = records.count { it.status == UploadHistoryStatus.FAILED }
                val cancelled = records.count { it.status == UploadHistoryStatus.CANCELLED }
                val summary = current.summary.copy(
                    total = records.size,
                    success = success,
                    failed = failed,
                    cancelled = cancelled,
                    finishedAt = record.finishedAt,
                )
                writeSession(UploadSession(summary, records))
                val index = readIndex().map { if (it.id == sessionId) summary else it }
                writeIndex(index)
                _sessions.value = index
            }
        }

    suspend fun finishSession(sessionId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = readSession(sessionId) ?: return@withLock
            val summary = current.summary.copy(
                finishedAt = System.currentTimeMillis().coerceAtLeast(current.summary.finishedAt),
            )
            writeSession(UploadSession(summary, current.records))
            val index = readIndex().map { if (it.id == sessionId) summary else it }
            writeIndex(index)
            _sessions.value = index
        }
    }

    suspend fun getSession(sessionId: String): UploadSession? = withContext(Dispatchers.IO) {
        mutex.withLock { readSession(sessionId) }
    }

    suspend fun deleteRecords(sessionId: String, recordIds: Collection<String>): UploadSession? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (recordIds.isEmpty()) return@withLock readSession(sessionId)
                val current = readSession(sessionId) ?: return@withLock null
                val idSet = recordIds.toSet()
                val records = current.records.filterNot { it.id in idSet }
                val success = records.count { it.status == UploadHistoryStatus.SUCCESS }
                val failed = records.count { it.status == UploadHistoryStatus.FAILED }
                val cancelled = records.count { it.status == UploadHistoryStatus.CANCELLED }
                val summary = current.summary.copy(
                    total = records.size,
                    success = success,
                    failed = failed,
                    cancelled = cancelled,
                    finishedAt = records.maxOfOrNull { it.finishedAt } ?: current.summary.finishedAt,
                )
                val updated = UploadSession(summary, records)
                writeSession(updated)
                val index = readIndex().map { if (it.id == sessionId) summary else it }
                writeIndex(index)
                _sessions.value = index
                updated
            }
        }

    suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            sessionFile(sessionId).delete()
            val index = readIndex().filterNot { it.id == sessionId }
            writeIndex(index)
            _sessions.value = index
        }
    }

    suspend fun clearAllSessions() = withContext(Dispatchers.IO) {
        mutex.withLock {
            dir.listFiles()?.forEach { it.delete() }
            writeIndex(emptyList())
            _sessions.value = emptyList()
        }
    }

    private fun sessionFile(id: String) = File(dir, "$id.json")

    private fun readIndex(): List<UploadSessionSummary> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(indexFile.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        UploadSessionSummary(
                            id = o.getString("id"),
                            startedAt = o.optLong("startedAt"),
                            finishedAt = o.optLong("finishedAt"),
                            total = o.optInt("total"),
                            success = o.optInt("success"),
                            failed = o.optInt("failed"),
                            cancelled = o.optInt("cancelled"),
                            remoteDir = o.optString("remoteDir"),
                            baseUrl = o.optString("baseUrl"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeIndex(items: List<UploadSessionSummary>) {
        val arr = JSONArray()
        items.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("startedAt", s.startedAt)
                    .put("finishedAt", s.finishedAt)
                    .put("total", s.total)
                    .put("success", s.success)
                    .put("failed", s.failed)
                    .put("cancelled", s.cancelled)
                    .put("remoteDir", s.remoteDir)
                    .put("baseUrl", s.baseUrl),
            )
        }
        indexFile.writeText(arr.toString())
    }

    private fun readSession(id: String): UploadSession? {
        val f = sessionFile(id)
        if (!f.exists()) return null
        return runCatching {
            val root = JSONObject(f.readText())
            val s = root.getJSONObject("summary")
            val summary = UploadSessionSummary(
                id = s.getString("id"),
                startedAt = s.optLong("startedAt"),
                finishedAt = s.optLong("finishedAt"),
                total = s.optInt("total"),
                success = s.optInt("success"),
                failed = s.optInt("failed"),
                cancelled = s.optInt("cancelled"),
                remoteDir = s.optString("remoteDir"),
                baseUrl = s.optString("baseUrl"),
            )
            val arr = root.getJSONArray("records")
            val records = buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val started = o.optLong("startedAt")
                    val finished = o.optLong("finishedAt")
                    add(
                        UploadHistoryRecord(
                            id = o.optString("id", UUID.randomUUID().toString()),
                            fileName = o.optString("fileName"),
                            fileSize = o.optLong("fileSize"),
                            remotePath = o.optString("remotePath"),
                            baseUrl = o.optString("baseUrl"),
                            status = runCatching {
                                UploadHistoryStatus.valueOf(o.optString("status", "FAILED"))
                            }.getOrDefault(UploadHistoryStatus.FAILED),
                            message = o.optString("message"),
                            startedAt = started,
                            finishedAt = finished,
                            durationMs = o.optLong(
                                "durationMs",
                                (finished - started).coerceAtLeast(0),
                            ),
                        ),
                    )
                }
            }
            UploadSession(summary, records)
        }.getOrNull()
    }

    private fun writeSession(session: UploadSession) {
        val s = session.summary
        val recordsArr = JSONArray()
        session.records.forEach { r ->
            recordsArr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("fileName", r.fileName)
                    .put("fileSize", r.fileSize)
                    .put("remotePath", r.remotePath)
                    .put("baseUrl", r.baseUrl)
                    .put("status", r.status.name)
                    .put("message", r.message)
                    .put("startedAt", r.startedAt)
                    .put("finishedAt", r.finishedAt)
                    .put("durationMs", r.durationMs),
            )
        }
        val root = JSONObject()
            .put(
                "summary",
                JSONObject()
                    .put("id", s.id)
                    .put("startedAt", s.startedAt)
                    .put("finishedAt", s.finishedAt)
                    .put("total", s.total)
                    .put("success", s.success)
                    .put("failed", s.failed)
                    .put("cancelled", s.cancelled)
                    .put("remoteDir", s.remoteDir)
                    .put("baseUrl", s.baseUrl),
            )
            .put("records", recordsArr)
        sessionFile(s.id).writeText(root.toString())
    }
}