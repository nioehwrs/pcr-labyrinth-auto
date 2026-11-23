package com.lmj.labyrinthauto.logic

import android.content.Context
import com.lmj.labyrinthauto.core.AppState
import com.lmj.labyrinthauto.core.OverlayStatus
import com.lmj.labyrinthauto.input.InputBackend
import com.lmj.labyrinthauto.reroll.InGameReroll
import com.lmj.labyrinthauto.reroll.RerollSettings

/**
 * **迷宫内"作废本局"的唯一出口**（2026-11-01 用户："以后可能还有其他的凹分条件，也就是需要新加撤退点，
 * 如果到时候一个个修撤退问题，会很麻烦"）。
 *
 * 以前每个撤退点都是"自己置 [MazeLoop.retreatRequested] + 各自决定要不要点 UI"，结果同样的 flag
 * 在不同刷开局方式下语义不一样（接口刷由服务层统一处理，局内刷有的靠下一局接管、有的就地撤）。
 * 现在统一走这里，**新增一个撤退条件 = 一行调用**，撤退怎么撤、撤不掉怎么办都在这一个地方管：
 *
 * | 刷开局方式 | 撤退动作 | 谁负责 |
 * |---|---|---|
 * | 接口刷 · 游戏内重启开 | 按现场收敛回 259：失败页直调撤退链（结束→撤退无报酬→二重确认）；预览页/裸地图 HUD 撤退链（LabyrinthRetireButton→确认）；编组页取消后落预览页同前；仅迷宫子菜单走返回键（旧局由下一局 reroll 的 API 清） | [com.lmj.labyrinthauto.logic.InGameRestart.backToTopFromMaze]；没成 → 服务层杀进程 → 重新登录 → `labyrinth/retire` 兜底 |
 * | 接口刷 · 游戏内重启关 | 不点任何 UI | 服务层：杀进程 → 重新登录 → `labyrinth/retire` |
 * | 局内刷 | 就地按现场撤（失败页/地图/选人面板/其他面板） | [InGameReroll.retreatNow] |
 * | 局内刷 · 撤不干净 | 标记下一局不沿用 → 下一局接管时再撤一次；仍失败则整轮停止（留人工） | 本模块 |
 *
 * ★**只有真的撤退才会作废本局**：黎明界的局是服务器状态，杀客户端/重启游戏只是 resume 同一局
 *  （2026-11-01 用户指出），所以"重启游戏"不能当兜底手段。
 *
 * 三种情况最后都置 [MazeLoop.retreatRequested] ⇒ `MazeLoop.run` 返回 false ⇒ 服务层
 * 「本局作废、不算完成、不消耗计划次数 → 继续下一局」。
 */
object MazeRetreat {

    /**
     * 撤退原因（日志/悬浮窗共用，新增条件时在这里加一项）。
     *
     * [tag] 会拼进归档文件名：`run_..._MAZE__ABORT_RETREAT_SCORE_时间.txt`，
     * 日志页据此显示「失败 · 凹分不足撤退」，不用打开文件猜是哪条策略。
     */
    enum class Reason(val cn: String, val tag: String) {
        FAIL_3_TIMES("同一节点连续失败 3 次", "FAIL3"),
        NO_PLAN("编组页算不出可用队伍", "NOPLAN"),
        SCORE_LOW("凹分不足", "SCORE"),

        /** ★凹分条件 2（断言模式）：遇到配置的事件/结果（2026-11-03） */
        EVENT_RULE("凹分：事件条件命中", "EVENT"),
    }

    /**
     * 最近一次撤退的原因（[MazeLoop] 归档本局时拿来拼 tag 和结束语）。
     * 每局开始由 `MazeLoop.runBody` 清空，所以不会串到下一局。
     */
    @Volatile
    var lastReason: Reason? = null
        private set

    /** 每局开始时清掉上一局的原因（由 `MazeLoop` 调用） */
    fun resetReason() {
        lastReason = null
    }

    /**
     * 请求作废本局（**所有撤退点都该调这个**）。
     *
     * @param ctx 为 null 时只置标记、不做任何 UI 动作（保守兜底，正常路径都有 ctx）
     * @param detail 具体数值，写进日志便于复盘（如 "基础分 24800 < 阈值 25000"）
     * @return 恒为 true（方便调用方直接 `return MazeRetreat.request(...)` 收尾本轮）
     */
    suspend fun request(
        input: InputBackend,
        ctx: Context?,
        reason: Reason,
        detail: String = "",
    ): Boolean {
        lastReason = reason
        val why = if (detail.isEmpty()) reason.cn else "${reason.cn}：$detail"
        AppState.addLog("撤退：$why → 本局作废，自动开下一局")
        OverlayStatus.setState("撤退：${reason.cn}")

        if (ctx == null) {
            AppState.addLog("[!] 撤退：拿不到 context → 只置标记，交给服务层/下一局兜底")
            MazeLoop.retreatRequested = true
            return true
        }

        if (RerollSettings.loadRerollMode(ctx) == RerollSettings.REROLL_API) {
            // 接口刷：客户端活着（游戏内重启开着）就点返回回 259、旧局交给 API 撤；否则杀进程重登再撤 —— 都由服务层管
            AppState.addLog("撤退（接口刷）：交给服务层（游戏内重启开 → 点返回回 259 + API 撤旧局；关 → 杀进程重登再撤）")
        } else {
            val ok = InGameReroll.retreatNow(input)
            if (ok) {
                AppState.addLog("撤退（局内刷）：已撤干净，下一局从黎明界 top 正常开局")
            } else {
                // ★这里**不能靠"重启游戏"兜底**（用户 2026-11-01 指出）：黎明界的局是**服务器状态**，
                //   杀客户端只会 resume 同一局，没撤掉就还在原地 —— 白花十几秒，局面一点没变。
                //   所以只做两件事：
                //   ① 标记下一局不沿用（否则下一局读到"路线合格"会直接接着打这局，连撤都不撤）；
                //   ② 由下一局接管时**再撤一次**（[InGameReroll.takeoverCurrentMaze] 的 forceRerollNext 分支）。
                //   两次都撤不掉 ⇒ runOneMaze 返回 false ⇒ 服务层结束整轮并报错，留给人工处理，
                //   而不是无限重试。
                AppState.addLog(
                    "[!] 撤退（局内刷）：就地没撤干净（多半是关不掉的面板）→ 标记下一局不沿用、接管时再撤一次；" +
                        "若仍失败会停止整轮运行，需要人工处理",
                )
                InGameReroll.forceRerollNext = true
            }
        }
        MazeLoop.retreatRequested = true
        return true
    }
}
