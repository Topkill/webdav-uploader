package com.webdav.uploader.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class UploadHistoryStatus {
    SUCCESS,
    FAILED,
    CANCELLED,
}

data class UploadHistoryRecord(
    val id: String = UUID.randomUUID().toString(),
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

private val Context.historyDataStore by preferencesDataStore(name = "upload_history")

class HistoryRepository(private val context: Context) {
    private object Keys {
        val itemsJson = stringPreferencesKey("items_json")
        val maxItems = intPreferencesKey("max_items")
    }

    companion object {
        const val DEFAULT_MAX_ITEMS = 100
        const val MIN_MAX_ITEMS = 10
        const val MAX_MAX_ITEMS = 500
    }

    val maxItemsFlow: Flow<Int> = context.historyDataStore.data.map { prefs ->
        (prefs[Keys.maxItems] ?: DEFAULT_MAX_ITEMS).coerceIn(MIN_MAX_ITEMS, MAX_MAX_ITEMS)
    }

    val historyFlow: Flow<List<UploadHistoryRecord>> = context.historyDataStore.data.map { prefs ->
        parse(prefs[Keys.itemsJson].orEmpty())
    }

    suspend fun setMaxItems(maxItems: Int) {
        val limited = maxItems.coerceIn(MIN_MAX_ITEMS, MAX_MAX_ITEMS)
        context.historyDataStore.edit { prefs ->
            prefs[Keys.maxItems] = limited
            val current = parse(prefs[Keys.itemsJson].orEmpty())
            prefs[Keys.itemsJson] = toJson(current.take(limited))
        }
    }

    /** 新增（上传完成时自动调用，也会给手动补录用） */
    suspend fun add(record: UploadHistoryRecord) {
        context.historyDataStore.edit { prefs ->
            val maxItems = (prefs[Keys.maxItems] ?: DEFAULT_MAX_ITEMS)
                .coerceIn(MIN_MAX_ITEMS, MAX_MAX_ITEMS)
            val current = parse(prefs[Keys.itemsJson].orEmpty())
            val next = (listOf(record) + current.filterNot { it.id == record.id }).take(maxItems)
            prefs[Keys.itemsJson] = toJson(next)
        }
    }

    /** 更新已有记录 */
    suspend fun update(record: UploadHistoryRecord): Boolean {
        var updated = false
        context.historyDataStore.edit { prefs ->
            val current = parse(prefs[Keys.itemsJson].orEmpty())
            if (current.none { it.id == record.id }) return@edit
            updated = true
            val next = current.map { if (it.id == record.id) record.copy(
                durationMs = (record.finishedAt - record.startedAt).coerceAtLeast(0),
            ) else it }
            prefs[Keys.itemsJson] = toJson(next)
        }
        return updated
    }

    /** 按 id 删除 */
    suspend fun delete(id: String): Boolean {
        var removed = false
        context.historyDataStore.edit { prefs ->
            val current = parse(prefs[Keys.itemsJson].orEmpty())
            val next = current.filterNot { it.id == id }
            removed = next.size != current.size
            prefs[Keys.itemsJson] = toJson(next)
        }
        return removed
    }

    /** 批量删除 */
    suspend fun deleteAll(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val idSet = ids.toSet()
        context.historyDataStore.edit { prefs ->
            val current = parse(prefs[Keys.itemsJson].orEmpty())
            prefs[Keys.itemsJson] = toJson(current.filterNot { it.id in idSet })
        }
    }

    suspend fun getById(id: String): UploadHistoryRecord? =
        historyFlow.first().firstOrNull { it.id == id }

    suspend fun clear() {
        context.historyDataStore.edit { prefs ->
            prefs[Keys.itemsJson] = "[]"
        }
    }

    suspend fun currentMaxItems(): Int = maxItemsFlow.first()

    private fun parse(raw: String): List<UploadHistoryRecord> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
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
        }.getOrDefault(emptyList())
    }

    private fun toJson(items: List<UploadHistoryRecord>): String {
        val arr = JSONArray()
        items.forEach { r ->
            arr.put(
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
        return arr.toString()
    }
}