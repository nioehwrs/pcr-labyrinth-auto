package com.lmj.labyrinthauto.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * ★条件 2「逻辑模式」token（T1~T8）的**按 enterId 续局快照**（2026-11-21 用户反馈）。
 *
 * 背景：用户在 0.7.11 报障——"断线续局后，条件2 的 token 归零：先走到 T1=2，停止/重启 App
 * 续局，下一个事件 T1 从 1 重新开始（应为 3），靠 token 计数的撤退规则失效"。根因是
 * [com.lmj.labyrinthauto.logic.LogicRule.Engine.tokens] 与 `logicFiredNode` 全是内存态，
 * 而重启 App 会走 `MazeLoop.runBody()` 无条件 `engine.reset()`。
 *
 * 修法：每个节点结束 fire 后，把 8 个 token + 已 fire 节点 key 连同当前 **enterId**
 * 一起写穿落盘（`files/logic_tokens.json`，只留最近一局）；重启后第一次 fire 时按
 * enterId 比对——同局 ⇒ 读回（token 接着算、同节点不重复 fire），新局 ⇒ 从 0 开始。
 *
 * 为什么按 enterId 而不是"有快照就恢复"：撤退重跑会开新局，新局 enterId 不同，
 * 必须清零；只有"游戏服务器那局还在、App 重启续接"才该恢复。
 */
object LogicTokenStore {
    private const val FILE = "logic_tokens.json"

    data class Snapshot(
        val enterId: Long,
        val tokens: IntArray,
        val firedNode: String,
    )

    /** 读回最近一局的快照；文件坏/字段不齐一律当没有（顶多 token 从头算，不炸主流程）。 */
    fun load(ctx: Context): Snapshot? = runCatching {
        val f = java.io.File(ctx.filesDir, FILE)
        if (!f.exists()) return null
        val o = JSONObject(f.readText())
        val eid = o.optLong("enterId", -1L)
        if (eid <= 0L) return null
        val arr = o.optJSONArray("tokens") ?: return null
        val t = IntArray(8) { arr.optInt(it) }
        Snapshot(eid, t, o.optString("firedNode", ""))
    }.getOrNull()

    /** 节点 fire 后立即写穿落盘（只留最近一局；写失败静默——下次顶多按新局算）。 */
    fun save(ctx: Context, enterId: Long, tokens: IntArray, firedNode: String) {
        if (enterId <= 0L) return
        runCatching {
            val o = JSONObject()
                .put("enterId", enterId)
                .put("tokens", JSONArray(tokens.map { it }))
                .put("firedNode", firedNode)
            java.io.File(ctx.filesDir, FILE).writeText(o.toString())
        }
    }

    /** 新局开跑时清掉上局快照（避免"没有 fire 过的新局"误恢复到上局 token）。 */
    fun clear(ctx: Context) {
        runCatching { java.io.File(ctx.filesDir, FILE).delete() }
    }
}
