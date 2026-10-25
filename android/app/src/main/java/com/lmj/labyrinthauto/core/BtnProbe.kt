package com.lmj.labyrinthauto.core

import com.lmj.labyrinthauto.frida.AgentApi
import kotlinx.coroutines.delay

/**
 * ★按钮"坐标会不会动"的一次性采样（2026-10-19 用户："先做采集试验，等采完一轮日志再看能不能用"）。
 *
 * 背景：局内刷开局里给「确认出发」「确认撤退」加了"等坐标站稳"之后，点空明显变少；
 * 想看看跑迷宫流程里的按钮是不是也有这个现象。但哪些会动、动多久，肉眼看不准 ⇒ 先采数据。
 *
 * 原则：**只读不点、不改变任何点击行为**。用便宜的 `labBtn`（缓存路径几十 ms，
 * 第一次会学一次路径 ~1.5s）连读几次坐标，把序列和最大漂移打进日志。
 * 同一个 (页面 tag, 按钮名) 只采一次，避免刷屏；每局开始调 [reset] 清零。
 */
object BtnProbe {

    /** 采样次数 / 间隔 */
    private const val TIMES = 5
    private const val GAP_MS = 120L

    /** 漂移 ≤ 这个值就算"站住了"（和 InGameReroll.waitStable 的 ±2px 同一把尺） */
    private const val STILL_PX = 2

    private val done = HashSet<String>()

    /**
     * 采一次：[tag] 用来区分页面（如 `遗物`、`事件`、`商店·3层`），[name] 是**节点名**
     * （`labBtn` 按名字定位；按文字/坐标定位的按钮不适用）。
     */
    suspend fun once(tag: String, name: String) {
        if (!done.add("$tag|$name")) return
        val pts = ArrayList<Pair<Int, Int>>()
        repeat(TIMES) { i ->
            // key 单独开一组（"probe|名字"），不污染别处学到的路径缓存
            val r = AgentApi.obj("labBtn", listOf("probe|$name", name, null, null), 2_000L)
            val x = r?.optInt("x", -1) ?: -1
            val y = r?.optInt("y", -1) ?: -1
            if (x > 0 && y > 0) pts.add(x to y)
            if (i < TIMES - 1) delay(GAP_MS)
        }
        if (pts.size < 2) {
            AppState.addLog("[漂移·$tag] $name：只读到 ${pts.size}/$TIMES 次（样本不够，判不了）")
            return
        }
        val dx = pts.maxOf { it.first } - pts.minOf { it.first }
        val dy = pts.maxOf { it.second } - pts.minOf { it.second }
        val d = maxOf(dx, dy)
        AppState.addLog(
            "[漂移·$tag] $name：" + pts.joinToString("") { "(${it.first},${it.second})" } +
                " Δmax=${d}px → " +
                if (d <= STILL_PX) "基本不动（不必等）" else "**会动**（点前值得等稳定）",
        )
    }

    /** 每局开始清一次，让每局每种页面都能采到一组 */
    fun reset() = done.clear()
}
