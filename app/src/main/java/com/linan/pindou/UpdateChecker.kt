package com.linan.pindou

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 应用内更新：查询 GitHub Release -> 比较 -> 下载 -> sha256 校验 -> 拉起安装。
 *
 * 零第三方依赖：HttpsURLConnection 查 API，系统 DownloadManager 负责下载
 * （自带通知栏进度，断点续传），FileProvider 暴露 APK 给系统安装器。
 *
 * ⚠️ 重要前提：GitHub API 匿名查询私有仓库只会返回 404，
 * 所以 [REPO] 指向的必须是**公开仓库**——`cedarln/paopao_pindou`（泡泡拼豆，
 * 源码与 Release 都放这里，公开可见；密钥库文件已 gitignore，不入库）。
 *
 * 发布新版本：把**签名后的** APK 传到该仓库的 Release，tag 形如 `vX.Y`，
 * 资产名以 `-release.apk` 结尾（本类优先挑它，避免把 .debug 后门包推给用户）。
 *
 * 本文件是唯一允许联网的地方（图纸生成完全离线），边界由 UpdateCheckerTest 守住。
 * 只在用户按下「检查更新」时调用，不做启动检查 / 后台轮询。
 */
object UpdateChecker {

    private const val OWNER = "cedarln"

    /** 检查更新查询的仓库，见类注释 ⚠️ —— 必须是公开仓库 */
    const val REPO = "paopao_pindou"

    /** 暴露成 public 让单测断言，免得仓库名被改错却没人发现 */
    const val API_LATEST = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    private const val UPDATE_DIR = "update"

    class ReleaseInfo(
        val tag: String,
        val apkUrl: String,
        val apkSize: Long,
        /** 小写 hex；GitHub Release 资产的 digest 字段（"sha256:..."）。没有则为空串 */
        val sha256: String,
        val apkName: String
    ) {
        /** "v1.2.3" -> "1.2.3" */
        val version: String get() = normalizeVersion(tag)
    }

    // ---------- 版本比较（纯逻辑，可单元测试） ----------

    /**
     * 从 tag 里提取版本号：找到第一个数字后，只保留数字和点，遇到其他字符就停。
     * "v1.2.3" -> "1.2.3"；"v1.2.0-rc1" -> "1.2.0"；"release-1.3" -> "1.3"。
     */
    fun normalizeVersion(tag: String): String {
        val v = tag.trim()
        val start = v.indexOfFirst { it in '0'..'9' }
        if (start < 0) return ""
        val sb = StringBuilder()
        var lastWasDot = false
        for (i in start until v.length) {
            val ch = v[i]
            if (ch in '0'..'9') {
                sb.append(ch)
                lastWasDot = false
            } else if (ch == '.') {
                if (!lastWasDot) {
                    sb.append('.')
                    lastWasDot = true
                }
            } else {
                break
            }
        }
        return sb.toString().trim('.')
    }

    /** 按数字段比较版本：负数 = a 更旧，0 = 相同，正数 = a 更新 */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }

    /** 最新 Release 的 tag 是否比当前安装的版本新 */
    fun hasNewer(latestTag: String, currentVersion: String): Boolean =
        currentVersion.isNotEmpty() &&
            compareVersions(normalizeVersion(latestTag), currentVersion) > 0

    // ---------- GitHub API（阻塞调用，请在子线程执行） ----------

    fun currentVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    fun fetchLatest(): ReleaseInfo {
        val conn = URL(API_LATEST).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val code = conn.responseCode
            if (code != 200) {
                throw IllegalStateException(
                    if (code == 404) "仓库不可见或还没有 Release（私有仓库匿名查不到）"
                    else "HTTP $code"
                )
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return parseLatest(body)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 资产挑选规则（单测守住）：
     * 1. 优先文件名以 `-release.apk` 结尾的资产；
     * 2. 否则取第一个名字不含 "debug" 的 .apk；
     * 3. 都没有 -> 报错。绝不把 .debug 包当更新推给用户。
     */
    fun pickApkAsset(assets: JSONArray): JSONObject? {
        var fallback: JSONObject? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name", "")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            if (name.endsWith("-release.apk", ignoreCase = true)) return a
            if (fallback == null && !name.contains("debug", ignoreCase = true)) fallback = a
        }
        return fallback
    }

    /** 从资产 digest 字段提取 sha256（GitHub 格式 "sha256:<hex>"），没有则空串 */
    fun extractSha256(asset: JSONObject): String {
        val digest = asset.optString("digest", "")
        return if (digest.startsWith("sha256:", ignoreCase = true)) {
            digest.substringAfter(':').trim().lowercase()
        } else ""
    }

    fun parseLatest(json: String): ReleaseInfo {
        val root = JSONObject(json)
        val tag = root.optString("tag_name", "")
        if (tag.isEmpty()) throw IllegalStateException("Release 数据里没有 tag_name")
        val assets = root.optJSONArray("assets") ?: JSONArray()
        val asset = pickApkAsset(assets)
            ?: throw IllegalStateException("最新 Release 里没有可发布的 APK（缺 -release.apk 资产）")
        val url = asset.optString("browser_download_url", "")
        if (url.isEmpty()) throw IllegalStateException("APK 资产缺少下载地址")
        return ReleaseInfo(
            tag = tag,
            apkUrl = url,
            apkSize = asset.optLong("size", 0),
            sha256 = extractSha256(asset),
            apkName = asset.optString("name", "pindou-update.apk")
        )
    }

    // ---------- sha256 校验（下载完必须校验通过才放开「安装」） ----------

    fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** expected 为空（发布页没给 digest）一律不通过——宁可报错也不装来路不明的包 */
    fun verify(file: File, expected: String): Boolean =
        expected.isNotEmpty() && file.exists() && sha256Of(file).equals(expected, ignoreCase = true)

    // ---------- 下载（系统 DownloadManager，自带通知栏进度） ----------

    /** 下载到应用私有目录 update/ 下，返回下载 id（-1 表示无法入队） */
    fun download(ctx: Context, url: String, fileName: String): Long {
        val req = DownloadManager.Request(Uri.parse(url))
        req.setTitle(fileName)
        req.setDescription("拼豆图纸 更新包")
        req.setMimeType("application/vnd.android.package-archive")
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        req.setDestinationInExternalFilesDir(ctx, null, "$UPDATE_DIR/$fileName")
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return dm.enqueue(req)
    }

    fun downloadedFile(ctx: Context, fileName: String): File =
        File(File(ctx.getExternalFilesDir(null), UPDATE_DIR), fileName)

    /** 轮询用：下载当前状态，0 表示查询不到该任务 */
    fun downloadStatus(ctx: Context, id: Long): Int {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cur = dm.query(DownloadManager.Query().setFilterById(id)) ?: return 0
        cur.use {
            return if (it.moveToFirst()) {
                val idx = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
                if (idx >= 0) it.getInt(idx) else 0
            } else 0
        }
    }

    /** 轮询用：已下载字节 / 总字节（总字节未知时为 -1），给进度对话框显示百分比 */
    fun downloadProgress(ctx: Context, id: Long): Pair<Long, Long> {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cur = dm.query(DownloadManager.Query().setFilterById(id)) ?: return 0L to -1L
        cur.use {
            return if (it.moveToFirst()) {
                val soFar = it.getLong(it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = it.getLong(it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                soFar to total
            } else 0L to -1L
        }
    }

    // ---------- 安装（Android 8+ 需要用户允许「安装未知应用」） ----------

    fun canInstallApk(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    /** 引导用户去开「安装未知应用」权限（回来后 onResume 会自动重试安装） */
    fun requestInstallPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
            )
        }
    }

    /** 拉起系统安装器；权限未开时跳设置页并返回 false */
    fun promptInstall(activity: Activity, apk: File): Boolean {
        if (!canInstallApk(activity)) {
            requestInstallPermission(activity)
            return false
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        val i = Intent(Intent.ACTION_VIEW)
        i.setDataAndType(uri, "application/vnd.android.package-archive")
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(i)
        return true
    }
}