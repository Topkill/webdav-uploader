package com.webdav.uploader.webdav

import com.webdav.uploader.data.WebDavConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class UploadProgress(
    val bytesSent: Long,
    val totalBytes: Long,
) {
    val percent: Float
        get() = if (totalBytes <= 0L) 0f else (bytesSent.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
}

class WebDavClient(private val config: WebDavConfig) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(config.connectTimeoutSec, TimeUnit.SECONDS)
        // 0 = 不限制，适合大文件上传
        .writeTimeout(if (config.writeTimeoutSec <= 0) 0 else config.writeTimeoutSec, TimeUnit.SECONDS)
        // 关键：等待 OpenList crypt/网盘处理完后的最终响应
        .readTimeout(config.readTimeoutSec, TimeUnit.SECONDS)
        .callTimeout(if (config.callTimeoutSec <= 0) 0 else config.callTimeoutSec, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val credential: String? =
        if (config.username.isBlank()) null
        else Credentials.basic(config.username, config.password)

    private fun rootUrl(): String = config.baseUrl.trimEnd('/') + "/"

    private fun joinUrl(remoteRelativePath: String): String {
        val root = rootUrl()
        val path = remoteRelativePath.trimStart('/')
        return root + path.split('/').joinToString("/") { segment ->
            java.net.URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")
        }
    }

    private fun Request.Builder.withAuth(): Request.Builder {
        credential?.let { header("Authorization", it) }
        return this
    }

    suspend fun probe(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(rootUrl())
                .method("PROPFIND", ByteArray(0).toRequestBody(null))
                .header("Depth", "0")
                .withAuth()
                .build()
            client.newCall(req).execute().use { resp ->
                when (resp.code) {
                    200, 207, 301, 302 -> "连接成功 HTTP ${resp.code}"
                    401 -> error("认证失败 HTTP 401：请检查用户名/密码")
                    else -> error("探测失败 HTTP ${resp.code}: ${resp.message}")
                }
            }
        }
    }

    suspend fun ensureDir(remoteDir: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val parts = remoteDir.trim('/').split('/').filter { it.isNotBlank() }
            if (parts.isEmpty()) return@runCatching
            var current = ""
            for (part in parts) {
                current = if (current.isEmpty()) part else "$current/$part"
                mkcol(current)
            }
        }
    }

    private fun mkcol(remoteDir: String) {
        val req = Request.Builder()
            .url(joinUrl(remoteDir).trimEnd('/') + "/")
            .method("MKCOL", null)
            .withAuth()
            .build()
        client.newCall(req).execute().use { resp ->
            // 201 created, 405 already exists, 301/302 redirect-ish, 409 parent missing handled by caller order
            if (resp.code !in listOf(201, 405, 301, 302, 200)) {
                // 部分 WebDAV 对已存在目录返回 405/409
                if (resp.code != 409) {
                    error("创建目录失败 $remoteDir HTTP ${resp.code}")
                }
            }
        }
    }

    suspend fun upload(
        remotePath: String,
        contentLength: Long,
        contentType: String?,
        input: InputStream,
        onProgress: (UploadProgress) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = object : RequestBody() {
                override fun contentType() =
                    (contentType ?: "application/octet-stream").toMediaTypeOrNull()

                override fun contentLength(): Long = contentLength

                override fun writeTo(sink: BufferedSink) {
                    input.source().use { source ->
                        var sent = 0L
                        val buffer = okio.Buffer()
                        while (true) {
                            val read = source.read(buffer, 64 * 1024)
                            if (read == -1L) break
                            sink.write(buffer, read)
                            sent += read
                            onProgress(UploadProgress(sent, contentLength))
                        }
                        sink.flush()
                    }
                }
            }

            val req = Request.Builder()
                .url(joinUrl(remotePath))
                .put(body)
                .withAuth()
                .header("Overwrite", "T")
                .build()

            // 这里会阻塞直到：请求体发完 + 服务端返回最终响应
            // readTimeout 就是“等 crypt/网盘处理完”的超时
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful && resp.code !in listOf(201, 204, 200)) {
                    val errBody = resp.body?.string()?.take(300).orEmpty()
                    error("上传失败 HTTP ${resp.code}: ${resp.message} $errBody")
                }
            }
        }
    }
}

