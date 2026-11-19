package com.linan.pindou

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.webkit.JavascriptInterface
import androidx.core.content.FileProvider
import java.io.File

/**
 * JS 桥：assets/index.html 里的图纸内核通过 window.Android 调用原生能力。
 * 注意 @JavascriptInterface 方法跑在 JavaBridge 线程，碰 UI 要回主线程。
 */
class WebAppBridge(private val activity: MainActivity) {

    /** 当前版本号，页面顶部显示用 */
    @JavascriptInterface
    fun version(): String = UpdateChecker.currentVersion(activity)

    /** 保存图纸 PNG 到相册（Pictures/拼豆图纸） */
    @JavascriptInterface
    fun saveImage(base64: String, name: String) {
        val data = stripPrefix(base64)
        activity.runOnUiThread { activity.saveImageFromJs(data, name) }
    }

    /** 把图纸 PNG 交给系统分享面板 */
    @JavascriptInterface
    fun shareImage(base64: String, name: String) {
        val data = stripPrefix(base64)
        Thread {
            try {
                val bytes = Base64.decode(data, Base64.DEFAULT)
                val dir = File(activity.cacheDir, "share").apply { mkdirs() }
                // 清掉旧分享文件，避免缓存堆积
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, name)
                file.writeBytes(bytes)
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.runOnUiThread {
                    activity.startActivity(Intent.createChooser(send, "分享拼豆图纸"))
                }
            } catch (e: Exception) {
                activity.toast("分享失败：${e.message}")
            }
        }.start()
    }

    /** 复制备料清单文本 */
    @JavascriptInterface
    fun copyText(text: String) {
        activity.runOnUiThread {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("拼豆备料清单", text))
            activity.toast("备料清单已复制到剪贴板")
        }
    }

    /** 应用内更新：查 GitHub Release -> 应用内下载 -> 校验 -> 拉起安装 */
    @JavascriptInterface
    fun checkUpdate() {
        activity.runOnUiThread { activity.checkUpdate() }
    }

    @JavascriptInterface
    fun toast(msg: String) = activity.toast(msg)

    private fun stripPrefix(s: String): String =
        if (s.startsWith("data:")) s.substringAfter(',') else s
}