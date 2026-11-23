package com.lmj.labyrinthauto.core

import android.content.Context
import com.lmj.labyrinthauto.frida.RootShell
import com.lmj.labyrinthauto.service.AutoForegroundService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ★断点续跑账本 + root 看门狗（2026-11-23，配套 MuMu lmkd "device is not responding" 恐慌杀）。
 *
 * 背景：连跑循环里，每局开头的"杀进程刷开局"会把 ~1.9GB 资产灌进内存（实测 1 分钟 RSS +900MB），
 * I/O/CPU 风暴让模拟器整体卡顿，MuMu 魔改 lmkd 进入恐慌模式 —— 无视 oom_score_adj、专杀
 * 游戏之外最大的后台进程，本 app 是名单上最大的 ⇒ 每轮重启游戏都可能被杀
 * （实测 2026-11-22 23:57、2026-11-23 04:05 两波；内存大小无关，12G/可用 10GB 照样杀）。
 *
 * 两件套：
 * 1. journal（app 私有目录 `run_journal.json`）：{total, guildPlan, idx, done, startedAt}。
 *    开跑时写、每局完成后推进、任何退出路径清空。恢复单元 = **整局**（每局本来就是全新
 *    刷开局，轮内状态无需保留），被杀的那局作废重跑。
 * 2. armed 标记（`/data/local/tmp/lm_auto_armed`，root 写）+ root 看门狗循环：
 *    每 10s 查一次 app 进程，不在就 monkey 拉回；标记被删则循环自我退出（**不拦用户正常关闭**，
 *    "该不该活着"由任务决定而不是进程）。
 *    App 被拉回后 [autoResumeIfInterrupted] 读 journal 自动接着跑。
 */
object RunJournal {

    /** 一轮连跑的进度快照：idx = 已完成的局数（第 idx 局完成后写入），done = 其中成功数 */
    data class State(
        val total: Int,
        val guildPlan: List<Int>,   // 多公会模式每局的公会 id（单公会 = 空表）
        val idx: Int,
        val done: Int,
        val startedAt: Long,
    )

    private const val MARKER = "/data/local/tmp/lm_auto_armed"
    private const val WATCHDOG_TAG = "LM_WATCHDOG_V1"
    private const val APP_PKG = "com.lmj.labyrinthauto"

    private fun journalFile(ctx: Context) = File(ctx.filesDir, "run_journal.json")

    /** 原子写（tmp + rename）：被杀瞬间写一半也不会留下坏 JSON */
    fun save(ctx: Context, s: State) {
        runCatching {
            val obj = JSONObject()
                .put("total", s.total)
                .put("guildPlan", JSONArray(s.guildPlan))
                .put("idx", s.idx)
                .put("done", s.done)
                .put("startedAt", s.startedAt)
            val f = journalFile(ctx)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(obj.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(obj.toString())
                tmp.delete()
            }
        }
    }

    fun load(ctx: Context): State? {
        return runCatching {
            val f = journalFile(ctx)
            if (!f.exists()) return null
            val obj = JSONObject(f.readText())
            val plan = mutableListOf<Int>()
            val arr = obj.optJSONArray("guildPlan") ?: JSONArray()
            for (i in 0 until arr.length()) plan.add(arr.optInt(i))
            State(
                total = obj.optInt("total"),
                guildPlan = plan,
                idx = obj.optInt("idx"),
                done = obj.optInt("done"),
                startedAt = obj.optLong("startedAt"),
            )
        }.getOrNull()?.takeIf { it.total > 0 }
    }

    /** 清账本（正常结束 / 手动停止 / 异常收尾都走这里；被外力杀掉时它没机会跑 = 正是续跑信号） */
    fun clear(ctx: Context) {
        runCatching { journalFile(ctx).delete() }
    }

    // ---------- armed 标记 + 看门狗循环（都要 root） ----------

    /**
     * 武装：写 armed 标记 + 保证看门狗循环在跑。
     * 循环里嵌 [WATCHDOG_TAG] 字样，`pkill -f` 靠它去重（老循环还活着就先杀再起新的）。
     */
    suspend fun arm(ctx: Context) {
        runCatching {
            RootShell.exec("mkdir -p /data/local/tmp; echo run > $MARKER", timeoutMs = 15_000L, direct = true)
            RootShell.exec("pkill -f $WATCHDOG_TAG", timeoutMs = 15_000L, direct = true)
            // 单行循环（常驻 shell / 独立 su 都按整行交给 sh 解析）：
            //   标记被删 → break 自我退出；app 进程不在 → monkey 拉回（无需知道 Activity 名）
            val loop =
                "while true; do [ -f $MARKER ] || break; " +
                    "pidof $APP_PKG >/dev/null 2>&1 || " +
                    "monkey -p $APP_PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; " +
                    "sleep 10; done # $WATCHDOG_TAG"
            RootShell.exec("nohup sh -c '$loop' >/dev/null 2>&1 &", timeoutMs = 15_000L, direct = true)
        }
    }

    /** 撤防：删标记（循环 10s 内自我退出）+ 立刻 pkill（不等那一拍） */
    suspend fun disarm(ctx: Context) {
        runCatching {
            RootShell.exec("rm -f $MARKER; pkill -f $WATCHDOG_TAG", timeoutMs = 15_000L, direct = true)
        }
        clear(ctx)
    }

    private suspend fun markerExists(): Boolean {
        val r = runCatching {
            RootShell.exec("[ -f $MARKER ] && echo LMJ_ARMED", timeoutMs = 15_000L, direct = true)
        }.getOrNull() ?: return false
        return r.text.contains("LMJ_ARMED")
    }

    /**
     * App 启动时的续跑检查：armed 标记 + journal 都在 = 上次运行**没走正常收尾**（被外力杀掉）。
     * 服务还在跑（用户开着 app 看进度）则不动；没在跑 → 自动起服务续跑剩余的局。
     */
    suspend fun autoResumeIfInterrupted(ctx: Context) {
        if (!markerExists()) return
        val s = load(ctx)
        if (s == null || s.idx !in 1 until s.total) {
            // 标记在但账本没了/已跑完 → 清账本，等看门狗循环自己退出
            clear(ctx)
            return
        }
        if (AppState.running.value) return   // 服务在跑（多半是用户开 app 看进度）→ 不动
        AppState.addLog(
            "═══ 检测到上次运行被中断（已完成 ${s.idx}/${s.total} 局）→ 自动续跑（看门狗拉回）═══",
        )
        AutoForegroundService.send(ctx, AutoForegroundService.ACTION_RUN_FULL)
    }
}
