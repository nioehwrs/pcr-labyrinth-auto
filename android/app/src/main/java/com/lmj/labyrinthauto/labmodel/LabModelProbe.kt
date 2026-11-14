package com.lmj.labyrinthauto.labmodel

import android.content.Context
import android.os.SystemClock
import com.lmj.labyrinthauto.core.AppState

/**
 * 模型探针（2026-11-14）—— 只做两件事：
 *   ① 把 [LabModel] 加载起来，打出三张 onnx 的加载耗时；
 *   ② 用**固定池**跑一次单队编队，与桌面版结果对拍（同一 quest + 同一池，应当一致）。
 *
 * 结果只打日志；模型缺失时静默跳过，失败也不影响任何功能。
 */
object LabModelProbe {

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
            runCatching {
                // 先保证组件就绪（首次会下载约 13MB；失败不影响任何功能）
                kotlinx.coroutines.runBlocking { LabModelInstaller.ensure(ctx.applicationContext) }
                probe(ctx.applicationContext)
            }.onFailure { AppState.addLog("$TAG 探针异常：${it.javaClass.simpleName}: ${it.message}") }
        }, "lab-model-probe").apply { isDaemon = true }.start()
    }

    private fun probe(ctx: Context) {
        // ★用组件的就绪判定（私有目录 + 兼容外部目录），不要只看 getExternalFilesDir
        if (!LabModelInstaller.ready(ctx)) return
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

        // ④ 三选一：3 个候选 × **两个目标**（EX + 5 区 BOSS）× 32 候选束搜索
        //   5 区 Boss 的关卡 id 用模型自己的敌人表反查（`labyrinth_boss.json` 的编号模型不认）
        val boss5 = LabModel.bossQuestOf(5, 301701)
        AppState.addLog("$TAG 5 区首领关卡（模型词表）= $boss5")
        val t3 = SystemClock.elapsedRealtime()
        val probeTargets = mutableListOf(770520101 to 1.0)
        boss5?.let { probeTargets.add(it to 1.0) }
        val pickIdx = LabModel.pickChar(
            listOf(135501, 110601, 129901),
            PROBE_POOL,
            probeTargets,
            2,
        )
        AppState.addLog(
            "$TAG 三选一：${SystemClock.elapsedRealtime() - t3}ms → " +
                (if (pickIdx == null) "算不出（回落规则版）" else "第 ${pickIdx + 1} 张"),
        )
    }
}
