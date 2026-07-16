package com.webdav.uploader.data

data class WebDavConfig(
    val baseUrl: String = "http://192.168.1.8:5244/dav",
    val username: String = "",
    val password: String = "",
    /** 连接超时（秒） */
    val connectTimeoutSec: Long = 60,
    /** 写超时：发送请求体期间（秒）。0 表示不限制 */
    val writeTimeoutSec: Long = 0,
    /** 读超时：等待服务端最终响应（秒）。crypt/网盘场景建议 1800+ */
    val readTimeoutSec: Long = 1800,
    /** 调用整体超时（秒）。0 表示不限制，避免大文件被整体掐断 */
    val callTimeoutSec: Long = 0,
    /** 远程目录，相对 WebDAV 根，如 crypt 或 加密盘/手机备份 */
    val remoteDir: String = "crypt",
)
