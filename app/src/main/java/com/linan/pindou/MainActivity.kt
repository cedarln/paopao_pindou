package com.linan.pindou

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * WebView 壳：图纸生成内核在 assets/index.html（完全离线），
 * 原生只负责选图、保存/分享、应用内更新。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 从「安装未知应用」设置页回来后要重试安装的包名 */
    private var pendingInstall: String? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val cb = filePathCallback ?: return@registerForActivityResult
        filePathCallback = null
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        cb.onReceiveValue(uris)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        // WebView 的网页内容不吃它自身的 padding（v1.1 真机验证过无效），
        // 所以套一层 FrameLayout，insets 加在外层容器上
        val root = android.widget.FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#F6F6F8")) // 与页面底色一致
            addView(
                webView,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        setContentView(root)
        // targetSdk 36 在 Android 15+ 强制 edge-to-edge，内容会画到状态栏底下。
        // 容器和 DecorView 两层都挂监听：谁先拿到 insets 谁加 padding 并 CONSUMED，
        // 后一层拿到 0 不会重复加；也防 AppCompat 包装层把 insets 截留成 0。
        val barsListener = androidx.core.view.OnApplyWindowInsetsListener { v, insets ->
            // 本 App 没手动开 edge-to-edge：API 35+ 因 targetSdk 36 被强制开启，才需要自己留白；
            // 老系统框架已留好状态栏位置，再加 padding 会双重留白
            if (android.os.Build.VERSION.SDK_INT < 35) {
                return@OnApplyWindowInsetsListener insets
            }
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, barsListener)
        // 状态栏那条露出 DecorView 底色，用白色和页头接成一体
        window.decorView.setBackgroundColor(android.graphics.Color.WHITE)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(window.decorView, barsListener)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // 双指缩放看图纸（色号要放大才看得清）
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            textZoom = 100
        }
        webView.addJavascriptInterface(WebAppBridge(this), "Android")
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                }
                pickImage.launch(Intent.createChooser(intent, "选择图片"))
                return true
            }
        }
        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onResume() {
        super.onResume()
        // 去开「安装未知应用」权限回来后，自动重试安装
        val name = pendingInstall
        if (name != null && UpdateChecker.canInstallApk(this)) {
            pendingInstall = null
            UpdateChecker.promptInstall(this, UpdateChecker.downloadedFile(this, name))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    fun toast(msg: String) {
        mainHandler.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    // ---------- 保存图纸到相册 ----------

    private var pendingSave: Pair<String, String>? = null

    fun saveImageFromJs(base64: String, name: String) {
        if (android.os.Build.VERSION.SDK_INT <= 28 &&
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            pendingSave = base64 to name
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 100)
            return
        }
        doSaveImage(base64, name)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val pending = pendingSave ?: return
        pendingSave = null
        if (requestCode == 100 && grantResults.isNotEmpty() &&
            grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            doSaveImage(pending.first, pending.second)
        } else {
            toast("未授予存储权限，无法保存到相册")
        }
    }

    private fun doSaveImage(base64: String, name: String) {
        Thread {
            try {
                val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                val resolver = contentResolver
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/泡泡拼豆")
                        put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                    } else {
                        val dir = java.io.File(
                            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES),
                            "泡泡拼豆"
                        ).apply { mkdirs() }
                        put(android.provider.MediaStore.Images.Media.DATA, java.io.File(dir, name).absolutePath)
                    }
                }
                val collection = if (android.os.Build.VERSION.SDK_INT >= 29) {
                    android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                val uri = resolver.insert(collection, values)
                    ?: throw IllegalStateException("MediaStore 插入失败")
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    values.clear()
                    values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                toast("已保存到相册 Pictures/泡泡拼豆/$name")
            } catch (e: Exception) {
                toast("保存失败：${e.message}")
            }
        }.start()
    }

    // ---------- 应用内更新（只在用户按下按钮时联网） ----------

    fun checkUpdate() {
        toast("正在检查更新…")
        Thread {
            try {
                val latest = UpdateChecker.fetchLatest()
                val current = UpdateChecker.currentVersion(this)
                mainHandler.post {
                    if (!UpdateChecker.hasNewer(latest.tag, current)) {
                        toast("已是最新版本 v$current")
                    } else {
                        onNewVersion(latest)
                    }
                }
            } catch (e: Exception) {
                toast("检查更新失败：${e.message}")
            }
        }.start()
    }

    private fun onNewVersion(latest: UpdateChecker.ReleaseInfo) {
        if (latest.sha256.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("发现新版本 ${latest.version}")
                .setMessage("发布资产缺少 sha256 digest，无法校验安装包完整性，已停止更新。请到发布页检查。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        val local = UpdateChecker.downloadedFile(this, latest.apkName)
        if (local.exists() && UpdateChecker.verify(local, latest.sha256)) {
            // 本地已有校验通过的同一版，不重复下载
            confirmInstall(latest, local)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("发现新版本 ${latest.version}")
            .setMessage("当前 v${UpdateChecker.currentVersion(this)}，是否下载更新？（${latest.apkSize / 1024 / 1024}MB）")
            .setPositiveButton("下载") { _, _ -> startUpdateDownload(latest) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmInstall(latest: UpdateChecker.ReleaseInfo, apk: java.io.File) {
        AlertDialog.Builder(this)
            .setTitle("新版本 ${latest.version} 已就绪")
            .setMessage("安装包已下载并通过 sha256 校验，立即安装？")
            .setPositiveButton("安装") { _, _ ->
                pendingInstall = latest.apkName
                if (UpdateChecker.promptInstall(this, apk)) pendingInstall = null
            }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun startUpdateDownload(latest: UpdateChecker.ReleaseInfo) {
        val id = UpdateChecker.download(this, latest.apkUrl, latest.apkName)
        if (id == -1L) {
            toast("无法启动系统下载服务")
            return
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("正在下载 ${latest.version}")
            .setMessage("等待网络…")
            .setCancelable(false)
            .setNegativeButton("取消") { _, _ ->
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).remove(id)
            }
            .show()
        val poll = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                when (UpdateChecker.downloadStatus(this@MainActivity, id)) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        dialog.dismiss()
                        val apk = UpdateChecker.downloadedFile(this@MainActivity, latest.apkName)
                        if (UpdateChecker.verify(apk, latest.sha256)) {
                            confirmInstall(latest, apk)
                        } else {
                            apk.delete()
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("更新失败")
                                .setMessage("安装包 sha256 校验未通过，已删除。请重试或到发布页反馈。")
                                .setPositiveButton("知道了", null)
                                .show()
                        }
                    }
                    DownloadManager.STATUS_FAILED, 0 -> {
                        dialog.dismiss()
                        toast("下载失败，请检查网络后重试")
                    }
                    DownloadManager.STATUS_RUNNING -> {
                        val (soFar, total) = UpdateChecker.downloadProgress(this@MainActivity, id)
                        dialog.setMessage(
                            if (total > 0) "下载中 ${soFar * 100 / total}%（${soFar / 1024 / 1024}.${soFar / 1024 % 1024 * 10 / 1024}MB / ${total / 1024 / 1024}MB）"
                            else "下载中 ${soFar / 1024}KB"
                        )
                        mainHandler.postDelayed(this, 600)
                    }
                    else -> {
                        // PENDING / PAUSED_WAITING_FOR_NETWORK / PAUSED_WAITING_TO_RETRY：
                        // DownloadManager 只肯在过了系统校验的网络上传字节，必须如实显示等待
                        dialog.setMessage("等待网络…（系统下载服务排队中，连上可用网络会自动继续）")
                        mainHandler.postDelayed(this, 600)
                    }
                }
            }
        }
        mainHandler.postDelayed(poll, 400)
    }
}