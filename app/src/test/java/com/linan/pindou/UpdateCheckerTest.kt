package com.linan.pindou

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守住更新模块的边界：
 * - API 地址必须指向公开分发仓库（私有仓库匿名查不到，更新会永远失败）
 * - 资产挑选必须优先 -release.apk，绝不把 .debug 包推给用户
 * - sha256 校验：没有 digest / 校验不过一律不放开安装
 */
class UpdateCheckerTest {

    // ---------- 版本号解析与比较 ----------

    @Test
    fun normalizeVersion_extractsNumericPrefix() {
        assertEquals("1.2.3", UpdateChecker.normalizeVersion("v1.2.3"))
        assertEquals("1.2.0", UpdateChecker.normalizeVersion("v1.2.0-rc1"))
        assertEquals("1.3", UpdateChecker.normalizeVersion("release-1.3"))
        assertEquals("", UpdateChecker.normalizeVersion("no-digits"))
        assertEquals("1.2", UpdateChecker.normalizeVersion("v1..2"))
    }

    @Test
    fun compareVersions_numericSegments() {
        assertTrue(UpdateChecker.compareVersions("1.10", "1.9") > 0)
        assertTrue(UpdateChecker.compareVersions("1.2", "1.2.1") < 0)
        assertEquals(0, UpdateChecker.compareVersions("1.2.0", "1.2"))
    }

    @Test
    fun hasNewer_ignoresSameAndOlder() {
        assertTrue(UpdateChecker.hasNewer("v1.1", "1.0"))
        assertFalse(UpdateChecker.hasNewer("v1.0", "1.0"))
        assertFalse(UpdateChecker.hasNewer("v0.9", "1.0"))
        assertFalse(UpdateChecker.hasNewer("v1.1", ""))
    }

    // ---------- API 地址边界 ----------

    @Test
    fun apiPointsToPublicReleaseRepo() {
        assertEquals(
            "https://api.github.com/repos/cedarln/paopao_pindou/releases/latest",
            UpdateChecker.API_LATEST
        )
    }

    // ---------- 资产挑选 ----------

    private fun asset(name: String, digest: String? = null): JSONObject {
        val o = JSONObject()
        o.put("name", name)
        o.put("browser_download_url", "https://example.com/$name")
        o.put("size", 1234L)
        if (digest != null) o.put("digest", digest)
        return o
    }

    @Test
    fun pickApk_prefersReleaseSuffixOverFirstApk() {
        val assets = JSONArray()
            .put(asset("pindou-v1.1.debug.apk"))
            .put(asset("pindou-v1.1.apk"))
            .put(asset("pindou-v1.1-release.apk"))
        val picked = UpdateChecker.pickApkAsset(assets)!!
        assertEquals("pindou-v1.1-release.apk", picked.getString("name"))
    }

    @Test
    fun pickApk_neverFallsBackToDebug() {
        val assets = JSONArray().put(asset("pindou-v1.1.debug.apk"))
        assertNull(UpdateChecker.pickApkAsset(assets))
    }

    @Test
    fun pickApk_fallsBackToPlainApk() {
        val assets = JSONArray()
            .put(asset("notes.txt"))
            .put(asset("pindou-v1.1.apk"))
        assertEquals("pindou-v1.1.apk", UpdateChecker.pickApkAsset(assets)!!.getString("name"))
    }

    @Test
    fun extractSha256_parsesGithubDigest() {
        assertEquals(
            "abc123",
            UpdateChecker.extractSha256(asset("a-release.apk", "sha256:ABC123"))
        )
        assertEquals("", UpdateChecker.extractSha256(asset("a-release.apk")))
        assertEquals("", UpdateChecker.extractSha256(asset("a-release.apk", "md5:abc")))
    }

    @Test
    fun parseLatest_fullFlow() {
        val json = JSONObject()
            .put("tag_name", "v1.1")
            .put(
                "assets", JSONArray()
                    .put(asset("pindou-v1.1.debug.apk"))
                    .put(asset("pindou-v1.1-release.apk", "sha256:deadbeef"))
            )
            .toString()
        val info = UpdateChecker.parseLatest(json)
        assertEquals("1.1", info.version)
        assertEquals("pindou-v1.1-release.apk", info.apkName)
        assertEquals("deadbeef", info.sha256)
        assertTrue(info.apkUrl.endsWith("pindou-v1.1-release.apk"))
    }

    @Test(expected = IllegalStateException::class)
    fun parseLatest_rejectsReleaseWithoutApk() {
        UpdateChecker.parseLatest(JSONObject().put("tag_name", "v9.9").put("assets", JSONArray()).toString())
    }

    // ---------- sha256 校验 ----------

    @Test
    fun verify_matchesKnownHash() {
        // "hello" 的 sha256
        val f = File.createTempFile("pindou-test", ".bin")
        f.deleteOnExit()
        f.writeText("hello")
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            UpdateChecker.sha256Of(f)
        )
        assertTrue(UpdateChecker.verify(f, "2CF24DBA5FB0A30E26E83B2AC5B9E29E1B161E5C1FA7425E73043362938B9824"))
        assertFalse(UpdateChecker.verify(f, "0000"))
    }

    @Test
    fun verify_refusesWhenDigestMissing() {
        val f = File.createTempFile("pindou-test", ".bin")
        f.deleteOnExit()
        f.writeText("hello")
        assertFalse(UpdateChecker.verify(f, ""))
    }
}