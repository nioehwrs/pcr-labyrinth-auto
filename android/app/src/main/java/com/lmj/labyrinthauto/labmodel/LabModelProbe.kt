package com.lmj.labyrinthauto.labmodel

import android.content.Context
import android.os.SystemClock
import com.lmj.labyrinthauto.core.AppState
import java.io.File

/**
 * 模型探针（2026-11-14）—— 只做两件事：
 *   ① 把 [LabModel] 加载起来，打出三张 onnx 的加载耗时；
 *   ② 用**固定池**跑一次单队编队，与桌面版结果对拍（同一 quest + 同一池，应当一致）。
 *
 * 结果只打日志；模型缺失时静默跳过，失败也不影响任何功能。
 */
object LabModelProbe {

    private const val DIR = "labmodel"
    private const val TAG = "[模型]"

    /** 对拍用：2026-11-14 那局的真实池（24 个，其中 2 个模型不认识会被丢掉） */
    private val PROBE_POOL = listOf(
        130801, 110301, 102501, 129901, 129301, 133101, 100801, 102201, 122301, 105001,
        125501, 135501, 133601, 125801, 116701, 180601, 110601, 135701, 129701, 108901,
        106001, 123001, 135801, 137401,
    )

    @Volatile
    private var started = false

    fun runOnce(ctx: Context) {
        if (started) return
        started = true
        Thread({
            runCatching { probe(ctx.applicationContext) }
                .onFailure { AppState.addLog("$TAG 探针异常：${it.javaClass.simpleName}: ${it.message}") }
        }, "lab-model-probe").apply { isDaemon = true }.start()
    }

    private fun probe(ctx: Context) {
        val dir = File(ctx.getExternalFilesDir(null), DIR)
        if (!File(dir, "metadata.json").exists() || !File(dir, "context.onnx").exists()) {
            return      // 没下载模型 → 什么都不做（正式版会走组件下载）
        }
        val t0 = SystemClock.elapsedRealtime()
        if (!LabModel.load(ctx)) return
        AppState.addLog("$TAG 加载完成：${SystemClock.elapsedRealtime() - t0}ms（三张 onnx + 词表）")

        // ① 单队（EX 关卡 770520101，30 秒目标）
        val t1 = SystemClock.elapsedRealtime()
        val one = LabModel.planTeam(770520101, PROBE_POOL, 1, 2)
        AppState.addLog(
            "$TAG 单队 EX：${SystemClock.elapsedRealtime() - t1}ms → $one" +
                "（桌面版基准 [110601, 122301, 129701, 130801, 180601]）",
        )

        // ② 三队（5 区 BOSS 770531601，90 秒目标）
        val t2 = SystemClock.elapsedRealtime()
        val three = LabModel.planTeam(770531601, PROBE_POOL, 3, 3)
        AppState.addLog("$TAG 三队 BOSS：${SystemClock.elapsedRealtime() - t2}ms → $three")

        // ③ 同一套 critic 给「我们选的队」和「桌面版选的队」各打一次分（判谁更强）
        if (one != null) {
            val desk = listOf(110601, 122301, 129701, 130801, 180601)
            LabModel.evalTeams(770520101, listOf(one.first(), desk), 2).forEach {
                AppState.addLog("$TAG 打分：$it")
            }
        }
    }
}
