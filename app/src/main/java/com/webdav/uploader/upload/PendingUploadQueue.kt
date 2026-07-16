package com.webdav.uploader.upload

import android.net.Uri
import java.util.concurrent.atomic.AtomicReference

/**
 * 进程内待传队列。
 * 避免把几千个 Uri 塞进 Intent/ClipData（Binder 限制 / 只挂前 500 权限的问题）。
 * 同一进程内 Activity 选完文件后写入，Service 读取。
 */
object PendingUploadQueue {
    private val ref = AtomicReference<List<Uri>>(emptyList())

    fun set(uris: List<Uri>) {
        ref.set(uris.toList())
    }

    fun take(): List<Uri> {
        return ref.getAndSet(emptyList())
    }

    fun peekCount(): Int = ref.get().size
}