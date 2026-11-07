package com.lmj.labyrinthauto.core

import android.content.Context
import com.lmj.labyrinthauto.net.bilibili.BilibiliGameSession
import com.lmj.labyrinthauto.net.bilibili.GameSessionResult
import com.lmj.labyrinthauto.reroll.LabyrinthLoginClient
import com.lmj.labyrinthauto.reroll.LoginOutcome

/**
 * **EX 装备的读 / 写**（2026-11-06）：走游戏 HTTP API（与 autopcr 完全同一套），
 * 不碰内存、不依赖 frida。
 *
 * 数据来源：`load/index` 的 `unit[]`，每个角色带 `ex_equip_slot[{slot, serial_id}]`
 * （slot 是 **1-based**：1/2/3；serial_id=0 表示空槽）。
 * 写入：`unit/equip_ex`，参数 `ex_equip_change_unit_list`（同 autopcr `ExtraEquipChangeUnit`）。
 *
 * 为什么不能只靠 `loginAndLoadProfile`：它虽然调了 `load/index`，但只取 `user_info`
 * 三个字段就把返回丢了；这里用登录后的 [BilibiliGameSession] 再请求一次拿完整数据。
 */
/** 一次快照：unitId → (slot → serialId) */
typealias ExEquipSnapshot = Map<Int, Map<Int, Int>>

object ExEquipService {

    private const val LOAD = "load/index"
    private const val EQUIP_EX = "unit/equip_ex"

    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        data class Err(val message: String) : Result<Nothing>
    }

    /** 登录（复用刷开局那套账号）并拉一次全量数据，抽出 EX 装备。 */
    suspend fun snapshot(ctx: Context): Result<ExEquipSnapshot> =
        withSession(ctx) { session ->
            val data = when (val r = session.request(LOAD, linkedMapOf("carrier" to "OPPO"))) {
                is GameSessionResult.Success -> r.data
                is GameSessionResult.Rejected -> return@withSession Result.Err("拉取数据被拒：${r.message}")
                is GameSessionResult.NetworkFailure -> return@withSession Result.Err(r.message)
                is GameSessionResult.ProtocolFailure -> return@withSession Result.Err(r.message)
            }
            val snap = parseSnapshot(data)
            if (snap.isEmpty()) dumpDiag(data)
            Result.Ok(snap)
        }

    /** 解析为空时的现场诊断（2026-11-06 首次实测 N=0）：把顶层键、unit 结构、首元素键都打出来 */
    private fun dumpDiag(data: Map<String, Any?>) {
        AppState.addLog("EX 装备诊断：load 顶层键 = ${data.keys.joinToString(",")}")
        val unit = data["unit_list"] ?: data["unit"]
        AppState.addLog("EX 装备诊断：unit_list 类型 = ${unit?.javaClass?.simpleName} ｜ 大小 = ${(unit as? List<*>)?.size ?: -1}")
        val first = (unit as? List<*>)?.firstOrNull() as? Map<*, *>
        if (first != null) {
            AppState.addLog("EX 装备诊断：unit[0] 键 = ${first.keys.joinToString(",")}")
            val slots = first["ex_equip_slot"]
            AppState.addLog("EX 装备诊断：ex_equip_slot 类型 = ${slots?.javaClass?.simpleName}")
            (slots as? List<*>)?.firstOrNull()?.let { s ->
                (s as? Map<*, *>)?.let { AppState.addLog("EX 装备诊断：slot[0] 键 = ${it.keys.joinToString(",")} 值=$it") }
            }
        } else {
            val alt = data.entries.firstOrNull { it.key.contains("unit", true) }
            AppState.addLog("EX 装备诊断：没有 unit 列表；相近键 = ${alt?.key ?: "无"}")
        }
    }

    /**
     * **开跑前一次登录搞定**：先把上次没还原的补上（若有），再存一份新快照。
     *
     * 为什么不分别调 [restore] + [snapshot]：那会连着登录两次（顶号无所谓，但多一次
     * 风控/超时的机会，也慢一倍）。这里一次 login 内完成全部动作。
     *
     * @param pendingId 上次未还原的记录 id（0 = 没有）
     */
    suspend fun prepareBeforeRun(ctx: Context, pendingId: Long): Result<ExEquipRecord> =
        withSession(ctx) { session ->
            // ① 上次没还原的先还原
            if (pendingId > 0L) {
                val old = ExEquipStore.all(ctx).firstOrNull { it.id == pendingId }
                if (old != null) {
                    when (val r = restoreWith(session, old)) {
                        is Result.Ok -> AppState.addLog("EX 装备：上次未还原，已补上 → ${r.value}")
                        is Result.Err -> AppState.addLog("EX 装备：上次未还原，补还原失败：${r.message}")
                    }
                } else {
                    ExEquipStore.clearPending(ctx)
                }
            }
            // ② 存一份新的（还原完重新读，拿到的就是最新状态）
            val data = when (val r = session.request(LOAD, linkedMapOf("carrier" to "OPPO"))) {
                is GameSessionResult.Success -> r.data
                is GameSessionResult.Rejected -> return@withSession Result.Err("拉取数据被拒：${r.message}")
                is GameSessionResult.NetworkFailure -> return@withSession Result.Err(r.message)
                is GameSessionResult.ProtocolFailure -> return@withSession Result.Err(r.message)
            }
            val snap = parseSnapshot(data)
            if (snap.isEmpty()) return@withSession Result.Err("没读到任何角色装备（unit 为空，可能数据格式变了）")
            val rec = ExEquipStore.save(ctx, snap, "开跑前自动保存")
            ExEquipStore.markPending(ctx, rec.id)
            Result.Ok(rec)
        }

    /**
     * 按快照还原。**先卸后装**（装备独占），只动快照覆盖到的槽位。
     * @return 人类可读的结论（成功/失败原因）
     */
    suspend fun restore(ctx: Context, record: ExEquipRecord): Result<String> =
        withSession(ctx) { session -> restoreWith(session, record) }

    /** [restore] 的主体：复用已登录的 session（开跑前 [prepareBeforeRun] 也用它） */
    private suspend fun restoreWith(
        session: BilibiliGameSession,
        record: ExEquipRecord,
    ): Result<String> {
            val data = when (val r = session.request(LOAD, linkedMapOf("carrier" to "OPPO"))) {
                is GameSessionResult.Success -> r.data
                is GameSessionResult.Rejected -> return Result.Err("拉取数据被拒：${r.message}")
                is GameSessionResult.NetworkFailure -> return Result.Err(r.message)
                is GameSessionResult.ProtocolFailure -> return Result.Err(r.message)
            }
            val current = parseSnapshot(data)
            // ★`user_ex_equip` 解析失败时**不做"装备已消失"判定**（否则会把整份快照都跳过）
            val alive = aliveSerialIds(data)
                .ifEmpty { record.state.values.flatMap { it.values }.toSet() }
            val plan = ExEquipPlan.build(record.state, current, alive)
            if (plan.isEmpty) {
                val extra = if (plan.missing.isEmpty()) "" else "（${plan.missing.size} 件装备已不存在，跳过）"
                return Result.Ok("已经和记录一致，无需还原$extra")
            }

            // ① 先卸（被占用的槽）② 再装
            if (plan.remove.isNotEmpty()) {
                when (val r = push(session, plan.remove)) {
                    is Result.Err -> return Result.Err("卸下旧装备失败：${r.message}")
                    is Result.Ok -> Unit
                }
            }
            if (plan.apply.isNotEmpty()) {
                when (val r = push(session, plan.apply)) {
                    is Result.Err -> return Result.Err("装回装备失败：${r.message}")
                    is Result.Ok -> Unit
                }
            }
            val note = if (plan.missing.isEmpty()) "" else "（跳过 ${plan.missing.size} 件已不存在的装备）"
            return Result.Ok("已还原：装上 ${plan.apply.size} 个槽位、卸下 ${plan.remove.size} 个$note")
    }

    // ────────────────────────── 内部 ──────────────────────────

    private suspend fun <T> withSession(
        ctx: Context,
        block: suspend (BilibiliGameSession) -> Result<T>,
    ): Result<T> {
        val acct = Accounts.selected(ctx)
            ?: return Result.Err("还没有账号 —— 先在账号卡里添加（B 服填账密 / 渠道服抓 uid+access_key）")
        val client = LabyrinthLoginClient(ctx, acct)
        return when (val r = client.login()) {
            is LoginOutcome.Session -> block(r.session)
            is LoginOutcome.CaptchaNeeded -> Result.Err("登录需要验证码，请先在「刷开局」页完成一次登录")
            is LoginOutcome.Failure -> Result.Err("登录失败：${r.message}")
        }
    }

    /** 把变更按 unit 分组后提交（同一角色多个槽位合并成一条，与 autopcr 一致） */
    private suspend fun push(
        session: BilibiliGameSession,
        changes: List<ExEquipPlan.Change>,
    ): Result<Unit> {
        val grouped = changes.groupBy { it.unitId }
        val list = grouped.map { (uid, items) ->
            linkedMapOf<String, Any?>(
                "unit_id" to uid,
                "ex_equip_slot" to items.map {
                    linkedMapOf<String, Any?>("slot" to it.slot, "serial_id" to it.serialId)
                },
                "cb_ex_equip_slot" to null,     // 会战装备不参与
            )
        }
        return when (val r = session.request(EQUIP_EX, linkedMapOf("ex_equip_change_unit_list" to list))) {
            is GameSessionResult.Success -> Result.Ok(Unit)
            is GameSessionResult.Rejected -> Result.Err(r.message)
            is GameSessionResult.NetworkFailure -> Result.Err(r.message)
            is GameSessionResult.ProtocolFailure -> Result.Err(r.message)
        }
    }

    /** `load.unit[]` → {unitId: {slot: serialId}}（只留非空槽） */
    @Suppress("UNCHECKED_CAST")
    private fun parseSnapshot(data: Map<String, Any?>): ExEquipSnapshot {
        val out = LinkedHashMap<Int, Map<Int, Int>>()
        // ★实测（2026-11-06）：load/index 的顶层键是 **`unit_list`**（不是 `unit`）
        val units = (data["unit_list"] ?: data["unit"]) as? List<*> ?: return out
        for (u in units) {
            val m = u as? Map<String, Any?> ?: continue
            // ★实测（2026-11-06）：角色 id 字段叫 **`id`**（不是 autopcr 里的 unit_id）
            val uid = ((m["unit_id"] ?: m["id"]) as? Number)?.toInt() ?: continue
            val slots = m["ex_equip_slot"] as? List<*> ?: continue
            val map = LinkedHashMap<Int, Int>()
            for (s in slots) {
                val sm = s as? Map<String, Any?> ?: continue
                val slot = (sm["slot"] as? Number)?.toInt() ?: continue
                val serial = (sm["serial_id"] as? Number)?.toInt() ?: 0
                if (serial > 0) map[slot] = serial
            }
            if (map.isNotEmpty()) out[uid] = map
        }
        return out
    }

    /** 现存的 EX 装备 serialId 集合（`load.user_ex_equip[]`），用于判断"装备已消失" */
    @Suppress("UNCHECKED_CAST")
    private fun aliveSerialIds(data: Map<String, Any?>): Set<Int> {
        val list = data["user_ex_equip"] as? List<*> ?: return emptySet()
        return list.mapNotNull { (it as? Map<String, Any?>)?.let { m -> (m["serial_id"] as? Number)?.toInt() } }
            .toSet()
    }
}
