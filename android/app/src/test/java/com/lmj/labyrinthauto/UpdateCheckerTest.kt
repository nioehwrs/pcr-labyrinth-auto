package com.lmj.labyrinthauto

import com.lmj.labyrinthauto.core.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 自动更新（2026-10-25）：**清单解析 / 版本比较**这些纯逻辑钉死。
 *
 * 为什么值得单测：更新判断错了的后果是"要么永远提示有新版本、要么永远收不到更新"，
 * 而这两件事在真机上都不容易发现（尤其"永远不提示"）。这里不碰网络、不碰 Android。
 *
 * 样例 JSON 与 `tools/release.py` 生成的 `latest.json` 结构一致（含 frida 块）。
 */
class UpdateCheckerTest {

    private val sample = """
        {
          "versionCode": 8,
          "versionName": "0.3.0",
          "apkUrl": "https://gitee.com/o/r/releases/download/v0.3.0/labyrinth-auto-0.3.0.apk",
          "sha256": "7ceea8f99046e292c4c1d311f90808bfd1b621e1cddf89c61baffc72f5dd0ab4",
          "size": 57922976,
          "frida": { "version": "17.16.4", "assets": { "x86_64": { "size": 111587168 } } },
          "force": false,
          "minVersionCode": 1,
          "channel": "stable",
          "notes": "## 0.3.0（2026-10-25）\n\n- 自动更新\n",
          "mirrors": [],
          "publishedAt": "2026-10-25 11:00:47"
        }
    """.trimIndent()

    @Test
    fun `解析 release py 生成的清单`() {
        val i = UpdateChecker.parse(sample)!!
        assertEquals(8, i.versionCode)
        assertEquals("0.3.0", i.versionName)
        assertEquals(57922976L, i.size)
        assertEquals(1, i.minVersionCode)
        assertFalse(i.force)
        assertTrue(i.sha256.length == 64)
        assertTrue("说明里应该带版本标题", i.notes.startsWith("## 0.3.0"))
        assertTrue("apk 直链要在", i.apkUrl.endsWith(".apk"))
    }

    @Test
    fun `缺 versionCode 或 apkUrl 就是无效清单`() {
        assertNull(UpdateChecker.parse("""{"versionName":"9.9.9"}"""))
        assertNull(UpdateChecker.parse("""{"versionCode":9}"""))
        assertNull(UpdateChecker.parse("""{"versionCode":0,"apkUrl":"x"}"""))
        assertNull(UpdateChecker.parse("这不是 JSON"))
        assertNull(UpdateChecker.parse(""))
    }

    @Test
    fun `等比版本号大小 `() {
        val i = UpdateChecker.parse(sample)!!
        assertTrue(i.isNewerThan(7))
        assertFalse("同一个 versionCode 不算新版（否则会无限提示更新）", i.isNewerThan(8))
        assertFalse(i.isNewerThan(9))
    }

    @Test
    fun `minVersionCode 是强制更新闸门`() {
        val i = UpdateChecker.parse(sample)!!
        assertFalse(i.isBlocked(7))          // 门槛 1，正常放行
        val strict = UpdateChecker.parse(sample.replace("\"minVersionCode\": 1", "\"minVersionCode\": 8"))!!
        assertTrue("低于门槛要强制更新", strict.isBlocked(7))
        assertFalse(strict.isBlocked(8))
    }

    @Test
    fun `sha256 校验是流式的且与 Python 结果一致`() {
        // "abc" 的 sha256 是公开的标准值，用来确认实现没写错
        val f = File.createTempFile("lmj-sha", ".bin")
        try {
            f.writeBytes("abc".toByteArray())
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                UpdateChecker.sha256Of(f),
            )
        } finally {
            f.delete()
        }
    }
}
