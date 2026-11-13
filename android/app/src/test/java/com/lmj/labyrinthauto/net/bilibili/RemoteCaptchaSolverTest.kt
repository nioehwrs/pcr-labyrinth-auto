package com.lmj.labyrinthauto.net.bilibili

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动打码与 autopcr `remoteValidator()` 对齐（2026-11-13 实测样本）：
 * `check/{uuid}` 的 `info` 是完整的 start_captcha 响应 + validate，必须整组替换。
 */
class RemoteCaptchaSolverTest {

    private val renew = """{"queue_num":2,"uuid":"4a161a71daf141e1afd5a229466d8786"}"""

    private val solved = """
        {"info":{"captcha_type":1,
         "challenge":"3e0c9df3923515153d03c88899c12e5e",
         "code":0,"gs":1,
         "gt":"245273d7fa73f7b657098bf7441fe12f",
         "gt_user_id":"b72ad4ee9e80444c892db85e7f873403",
         "requestId":"64c3411a5e224dc2b9029629c1a3bffc",
         "server_message":"","timestamp":"1794541733328",
         "type":"click|word",
         "validate":"077cece658acf60a184df7c1f6643e2a"}}
    """.trimIndent()

    @Test
    fun `解析实测 renew 响应`() {
        assertEquals("4a161a71daf141e1afd5a229466d8786", RemoteCaptchaSolver.parseUuid(renew))
        assertNull(RemoteCaptchaSolver.parseUuid("{}"))
        assertNull(RemoteCaptchaSolver.parseUuid("not json"))
    }

    @Test
    fun `实测打码结果返回整组 challenge 与 gt_user_id`() {
        val state = RemoteCaptchaSolver.parseCheck(solved) as RemoteCaptchaSolver.Check.Solved
        assertEquals("3e0c9df3923515153d03c88899c12e5e", state.solution.challenge)
        assertEquals("b72ad4ee9e80444c892db85e7f873403", state.solution.gtUserId)
        assertEquals("077cece658acf60a184df7c1f6643e2a", state.solution.validate)
    }

    @Test
    fun `排队 失败 运行中 与未知结果`() {
        assertTrue(RemoteCaptchaSolver.parseCheck("""{"queue_num":2}""") is RemoteCaptchaSolver.Check.Queued)
        assertEquals(2, (RemoteCaptchaSolver.parseCheck("""{"queue_num":"2"}""") as RemoteCaptchaSolver.Check.Queued).num)
        assertTrue(RemoteCaptchaSolver.parseCheck("""{"info":"fail"}""") is RemoteCaptchaSolver.Check.Failed)
        assertTrue(RemoteCaptchaSolver.parseCheck("""{"info":"url invalid"}""") is RemoteCaptchaSolver.Check.Failed)
        assertTrue(RemoteCaptchaSolver.parseCheck("""{"info":"in running"}""") == RemoteCaptchaSolver.Check.Running)
        assertTrue(RemoteCaptchaSolver.parseCheck("""{"info":{"challenge":"c"}}""") == RemoteCaptchaSolver.Check.Unknown)
        assertTrue(RemoteCaptchaSolver.parseCheck("not json") == RemoteCaptchaSolver.Check.Unknown)
    }

    @Test
    fun `排队与运行中之后拿到结果`() = runBlocking {
        val calls = mutableListOf<String>()
        val waits = mutableListOf<Long>()
        val queue = ArrayDeque(
            listOf(
                renew,
                """{"queue_num":2}""",
                """{"info":"in running"}""",
                solved,
            )
        )
        val solver = RemoteCaptchaSolver(
            fetch = { url -> calls.add(url); queue.removeFirst() },
            sleep = { waits.add(it) },
        )
        val result = solver.solve()
        assertNotNull(result)
        assertEquals("077cece658acf60a184df7c1f6643e2a", result!!.validate)
        assertEquals(4, calls.size)
        assertTrue(calls[0].endsWith("/geetest_renew"))
        assertTrue(calls[1].endsWith("/check/4a161a71daf141e1afd5a229466d8786"))
        assertEquals(listOf(20_000L, 8_000L), waits)
    }

    @Test
    fun `排队过长或服务异常都回退手动`() = runBlocking {
        val longQueue = RemoteCaptchaSolver(
            fetch = { if (it.endsWith("renew")) renew else """{"queue_num":35}""" },
            sleep = { },
        )
        assertNull(longQueue.solve())

        val broken = RemoteCaptchaSolver(
            fetch = { error("offline") },
            sleep = { },
        )
        assertNull(broken.solve())
    }
}
