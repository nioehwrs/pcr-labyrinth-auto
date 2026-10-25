package com.lmj.labyrinthauto.input

import com.lmj.labyrinthauto.frida.RootShell

/**
 * 点击后端 ②：**root**（`su -c "input tap x y"`）—— 与 PC 版 `adb shell input tap` 是**同一条路**。
 *
 * 为什么它现在是**默认**（2026-10-14 定案，翻转了 `PLAN.md §0.2` 的"先做无障碍"）：
 *   1. frida 那条链路本来就要 root（起 frida-server / 读 `/proc/<pid>/maps` 判就绪 / `am start`
 *      调游戏 / `force-stop` 自愈），所以走 root 点击**不引入任何新依赖**；
 *   2. 而无障碍有几处脆弱（实测都踩过）：授权会被 **force-stop / 重启 / 从最近任务划掉** 摘掉、
 *      模拟器偶发丢手势（`§8`）、还要人工开启；
 *   3. PC 版一直就是 `input tap`，走同一条路，两边行为少一个变量（坐标空间/时序）。
 *
 * 坐标：**直接透传**（参考系 = 显示坐标）。`input` 吃的就是显示坐标，PC 版也是拿 agent 的
 * 1920×1080 坐标直接 `input tap`。所以**调用前必须确认游戏在前台**（方向一致才谈得上同一套坐标）
 * —— 这道闸门在 `Foreground` + 调用方（见 `Navigator`）。
 *
 * 无障碍后端已移除（2026-10-16）：没 root 时 frida 都用不了，没有"兜底"可言。
 */
class RootInputBackend : InputBackend {

    override val name: String = "root"

    override fun isAvailable(): Boolean = RootShell.form != null

    override suspend fun tap(x: Int, y: Int): InputResult = run("input tap $x $y")

    override suspend fun swipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMillis: Long,
    ): InputResult = run("input swipe $x1 $y1 $x2 $y2 ${durationMillis.coerceAtLeast(1L)}")

    override suspend fun back(): InputResult = run("input keyevent 4")

    /**
     * 跑一条 `input` 命令。
     * `input` 是系统里的可执行文件（`/system/bin/input`，在 PATH 里），root 直接跑即可；
     * 退出码 0 = 系统已接受这次注入（**不代表游戏一定响应** —— 与 PC 版一样，"点后回读校验"是调用方的事）。
     */
    private suspend fun run(cmd: String): InputResult {
        if (!isAvailable()) return InputResult.Unavailable
        val r = RootShell.exec(cmd, 15_000L)
        return if (r.exit == 0) {
            InputResult.Ok
        } else {
            InputResult.Failed("$cmd → ${r.text.take(140)}")
        }
    }
}
