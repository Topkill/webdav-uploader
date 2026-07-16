# WebDAV 上传器（可自定义超时）

专为 **OpenList Desktop + crypt 网盘 + 局域网** 场景写的轻量安卓上传工具。

解决的问题：普通文件管理器 WebDAV 在「数据发完后还要等 crypt/网盘处理」时读超时太短，**客户端已报失败，OpenList 后台其实还在传**。

## 功能

- 自定义 WebDAV 地址 / 账号 / 密码
- 自定义远端目录（例如 crypt 挂载路径）
- **可自定义超时**
  - 连接超时 `connect`
  - **读超时 `read`（等最终完成结果，默认 1800 秒）**
  - 写超时 `write`（0 = 不限制）
  - 整体超时 `call`（0 = 不限制）
- 多文件选择上传
- 前台服务 + 通知栏进度
- 支持系统「分享到」此 App
- 上传进度到 100% 后会显示 **等待服务端完成(crypt/网盘)...**

## 环境要求

- Android 8.0+（API 26）
- 手机与 Windows 同一局域网
- OpenList Desktop 已开启 WebDAV

## 用 Android Studio 编译

1. 安装 [Android Studio](https://developer.android.com/studio)
2. **Open** 本目录 `webdav-uploader`
3. 等待 Gradle 同步
4. 连接手机或开模拟器
5. Run `app`

首次同步会下载 Gradle / SDK 依赖，需能访问 Google Maven。

生成 Debug APK：

```text
Build -> Build Bundle(s) / APK(s) -> Build APK(s)
```

或命令行（需本机已装 JDK 17 + Android SDK）：

```powershell
cd D:\MyOpenSource\webdav-uploader
.\gradlew.bat assembleDebug
```

APK 输出：

```text
app\build\outputs\apk\debug\app-debug.apk
```

## 使用步骤

1. 安装 APK，允许通知权限（前台上传进度）
2. 填写：
   - **WebDAV URL**：`http://电脑局域网IP:端口/dav`  
     例：`http://192.168.1.8:5244/dav`
   - 用户名 / 密码：OpenList WebDAV 凭证
   - **远端目录**：crypt 路径，如 `crypt` 或 `加密盘/手机备份`
3. 超时建议（OpenList crypt + 网盘）：
   - 连接超时：`60`
   - **读超时：`1800`～`3600`**
   - 写超时：`0`
   - 整体超时：`0`
4. 点 **保存设置** → **测试连接**
5. **选择文件上传**
6. 进度到 100% 后若停住，是在等服务端 crypt/网盘，**不要取消、不要重传**

## 为什么读超时要这么大？

上传链路：

```text
手机 App
  → PUT 数据到 OpenList Desktop
  → crypt 加密
  → 上传到底层网盘
  → 才返回最终 HTTP 成功
```

`read timeout` 控制的是：**请求体发完之后，还能等多久最终响应**。  
这正是普通文件管理器最容易假失败的点。

## 注意

- 默认允许 HTTP 明文（局域网需要）
- 上传中尽量保持 App/前台服务存活，避免系统杀后台
- 客户端若仍失败，先到 OpenList 看任务/文件是否已在传或已完成，**避免重复上传**
- 本工具做 PUT 上传与超时控制，不做文件浏览器

## 项目结构

```text
app/src/main/java/com/webdav/uploader/
  MainActivity.kt
  data/          # 配置与 DataStore 持久化
  webdav/        # OkHttp WebDAV 客户端（超时核心）
  upload/        # 前台上传服务
  ui/            # Compose 界面
```

## 许可证

MIT

## GitHub Actions 编译

仓库已包含工作流：`.github/workflows/android.yml`

- 触发：push / PR 到 `main` 或 `master`，也可在 Actions 页手动 Run workflow
- 产物：
  - `app-debug`：可直接安装的 debug APK
  - `app-release-unsigned`：未签名 release APK
- 下载：GitHub → Actions → 选中一次成功的 run → Artifacts

## 页面结构

- **主页**：查看已落盘连接信息、选择文件上传、进度/日志
- **更多 → 设置**：WebDAV 连接配置、超时、历史上限（全部落盘）
- **更多 → 上传历史**：仅查看 / 删除 / 清空（不可手动新增或编辑）

## 上传历史

- 成功 / 失败 / 取消自动落盘
- 仅支持查看与删除
- 历史上限在设置页配置（默认 100，范围 10–500）
- 存储：DataStore（`upload_history`）
