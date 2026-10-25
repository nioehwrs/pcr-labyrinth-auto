package com.lmj.labyrinthauto.reroll

import android.content.Context
import com.lmj.labyrinthauto.core.AppState
import com.lmj.labyrinthauto.core.CenterTapGate
import com.lmj.labyrinthauto.logic.party.PartyPlan
import com.lmj.labyrinthauto.net.bilibili.BilibiliGameSession
import com.lmj.labyrinthauto.net.labyrinth.BilibiliLabyrinthApi
import com.lmj.labyrinthauto.net.labyrinth.LabyrinthOperationResult
import kotlinx.coroutines.CompletableDeferred

/**
 * 把「登录 → 刷开局 → 存结果」串成一个对 UI 友好的编排层：
 *  - 状态全部写进 [AppState]（rerollBusy / rerollStatus / rerollCaptcha / rerollResultSummary），
 *    HomeScreen 直接 collect 这些 StateFlow 显示进度与验证码；
 *  - 成功后把结果写进 [RerollState]，供 [com.lmj.labyrinthauto.logic.MazeLoop] 开局时消费。
 *  - [reroll] 是给「一键黎明界」用的阻塞版：验证码出现时会挂起，直到用户在验证码弹窗里解完。
 */
object RerollUseCase {
    private var client: LabyrinthLoginClient? = null
    private var session: BilibiliGameSession? = null
    /** 验证码等待中的挂起点（[submitCaptcha] 解出后唤醒，[reroll] 才能继续） */
    private var captchaDeferred: CompletableDeferred<Boolean>? = null

    /** ★撤退重跑后置位：下一局刷开局**不沿用**当前局（哪怕合格——作废局的路线本来就合格），
     *  直接废掉重 enter。由服务的撤退重跑分支置位、[runReroll] 消费后清零。 */
    @Volatile
    var forceRerollNext = false

    /**
     * 仅刷开局（不跑迷宫）：保存账密后调 [reroll]。
     * 凭证只来自 UI 输入（[LabyrinthLoginClient] 不做任何令牌回退），账密留空会直接登录失败。
     */
    suspend fun login(context: Context, account: String, password: String) {
        RerollSettings.saveCredentials(context, account, password)
        reroll(context)
    }

    /**
     * 刷开局（登录 → enter/retire 循环直到合格）。返回 true = 已拿到合格开局（[RerollState.result] 已就位）。
     * 若登录触发验证码，会**挂起**直到用户在验证码弹窗里解完（[submitCaptcha] 唤醒），再继续刷；
     * 这样「一键黎明界」里能直接 `val ok = reroll(ctx)` 等待结果。
     */
    suspend fun reroll(context: Context): Boolean {
        // ★刷初始前先关"屏幕中心加速"（2026-10-22 用户）：新开局的选人页绝不能继承上一局的开关。
        CenterTapGate.reset("刷初始前")
        val (account, password) = RerollSettings.loadCredentials(context)
        RerollSettings.saveCredentials(context, account, password)
        AppState.setRerollBusy(true)
        AppState.setRerollStatus("正在登录 B 站…")
        client = LabyrinthLoginClient(context)
        return when (val r = client!!.login(account, password)) {
            is LoginOutcome.Session -> {
                session = r.session
                AppState.setRerollStatus("登录成功，开始刷开局")
                runReroll(context)
            }
            is LoginOutcome.CaptchaNeeded -> {
                AppState.setRerollCaptcha(r.challenge)
                AppState.addLog("出现验证码，请在弹窗里完成验证后继续")
                val d = CompletableDeferred<Boolean>()
                captchaDeferred = d
                val ok = d.await()
                captchaDeferred = null
                ok
            }
            is LoginOutcome.Failure -> {
                AppState.addLog("刷开局登录失败：${r.message}")
                AppState.setRerollStatus("登录失败：${r.message}")
                AppState.setRerollBusy(false)
                false
            }
        }
    }

    /**
     * ★**仅重新登录**（不刷开局），返回 true = [session] 已刷新。
     *
     * 为什么撤退前要它（2026-10-19 用户纠正了"客户端在线导致 API 被拒"的错误推断）：
     * 游戏客户端**不维持登录态**——别处登录就无条件挤下线；而每局开始我们"reroll 登录 →
     * 杀进程重启游戏"后，**客户端冷启动用本地存档 token 自动登录，把 API 会话挤掉**。
     * 所以后面触发撤退时 [retire] 拿着的是**已失效的会话**（服务器回"连接中断。回到标题界面。"）。
     * 撤退前用保存的凭据重新登录一次（顺带把客户端挤掉——局已作废，无所谓）即可。
     */
    suspend fun ensureLogin(context: Context): Boolean {
        val (account, password) = RerollSettings.loadCredentials(context)
        if (account.isBlank() || password.isBlank()) {
            AppState.addLog("撤退：没有保存的账号密码，无法重新登录 → 交给下一局刷开局兜底撤退")
            return false
        }
        AppState.setRerollStatus("重新登录 B 站…")
        client = LabyrinthLoginClient(context)
        return when (val r = client!!.login(account, password)) {
            is LoginOutcome.Session -> {
                session = r.session
                AppState.setRerollStatus("重新登录成功")
                true
            }
            is LoginOutcome.Failure -> {
                AppState.addLog("撤退：重新登录失败（${r.message}）→ 交给下一局刷开局兜底撤退")
                false
            }
            is LoginOutcome.CaptchaNeeded -> {
                // 自动流程没人解验证码；不挂起干等，交下一局兜底
                AppState.addLog("撤退：重新登录触发验证码 → 交给下一局刷开局兜底撤退")
                false
            }
        }
    }

    /**
     * ★按策略「撤退重跑」：把当前局从服务端撤掉（`labyrinth/retire`，不算完成）。
     * 会话失效/撤退失败都返回 false —— 下一局的刷开局（[reroll]）本身会再兜底撤退一次，不会卡住。
     */
    suspend fun retire(): Boolean {
        val s = session
        if (s == null) {
            AppState.addLog("撤退：登录会话已失效 → 交给下一局刷开局兜底撤退")
            return false
        }
        val api = BilibiliLabyrinthApi(s)
        val top = when (val r = api.top()) {
            is LabyrinthOperationResult.Success -> r.value
            is LabyrinthOperationResult.Failure -> {
                AppState.addLog("撤退：读不到当前局（${r.message}）→ 交给下一局刷开局兜底撤退")
                return false
            }
        }
        val eid = top.enterId
        if (eid == null) {
            AppState.addLog("撤退：服务端已无进行中的局（可能已结算）")
            return true
        }
        return when (val r = api.retire(eid)) {
            is LabyrinthOperationResult.Success -> {
                AppState.addLog("撤退成功：enterId=$eid（本局作废，不计入完成）")
                true
            }
            is LabyrinthOperationResult.Failure -> {
                AppState.addLog("撤退失败：${r.message} → 交给下一局刷开局兜底撤退")
                false
            }
        }
    }

    suspend fun submitCaptcha(context: Context, validate: String) {
        AppState.setRerollCaptcha(null)
        AppState.setRerollStatus("验证中…")
        when (val r = client?.completeCaptcha(validate)) {
            is LoginOutcome.Session -> {
                session = r.session
                val ok = runReroll(context)
                captchaDeferred?.complete(ok)
            }
            is LoginOutcome.CaptchaNeeded -> {
                AppState.setRerollCaptcha(r.challenge)
                captchaDeferred?.complete(false)
            }
            is LoginOutcome.Failure -> {
                AppState.addLog("刷开局验证失败：${r.message}")
                AppState.setRerollStatus("验证失败：${r.message}")
                AppState.setRerollBusy(false)
                captchaDeferred?.complete(false)
            }
            null -> {
                AppState.setRerollStatus("登录会话已失效，请重新登录")
                AppState.setRerollBusy(false)
                captchaDeferred?.complete(false)
            }
        }
    }

    private suspend fun runReroll(context: Context): Boolean {
        val session = session ?: run {
            AppState.setRerollBusy(false)
            return false
        }
        val config = RerollSettings.loadConfig(context)
        PartyPlan.loadTables(context)
        val api = BilibiliLabyrinthApi(session)
        // ★撤退重跑置位 ⇒ 下一局不沿用废局（见 RerollController.skipResume 的说明），用完即清
        val skipResume = forceRerollNext
        forceRerollNext = false
        var lastAttempt = 0
        val controller = RerollController(
            api, config, skipResume = skipResume,
            onProgress = { p ->
                AppState.setRerollStatus(
                    "第 ${p.attempt} 次：${p.stage}" + (if (p.lastFail.isNullOrBlank()) "" else "（${p.lastFail}）"),
                )
                // ★每次刷（一次 enter/判定）且未命中时打一行简短日志，避免过程静默被误判为卡死
                if (p.attempt > 0 && p.stage != "命中目标路线开局") {
                    AppState.addLog(
                        "刷开局·第 ${p.attempt} 次：" + p.stage +
                            (if (p.lastFail.isNullOrBlank()) "" else "（${p.lastFail.take(24)}）"),
                    )
                }
                lastAttempt = p.attempt
            },
        )
        AppState.setRerollStatus("开始刷开局…")
        return when (val r = controller.run()) {
            is RerollResult.Success -> {
                RerollState.result = r
                // ★区分"沿用"和"重刷"（2026-10-16 用户指出：沿用也打"刷到合格开局"⇒ 日志看起来
                //   像重刷过、让人误判"重开了一局"）。PC `reroll.reused` 同款语义。
                val head = if (r.reused) "当前开局已合格 → 直接沿用（未重刷）" else "刷到合格开局（重开过）"
                val summary =
                    "$head：enter_id=${r.enterId} 难度=${r.difficulty} " +
                        "3区Boss=${r.bossByArea[3]} 5区Boss=${r.bossByArea[5]} 价值=${r.actual}/${r.theory}"
                AppState.setRerollResultSummary(summary)
                AppState.setRerollStatus("完成：$summary")
                AppState.addLog(
                    if (r.reused) "当前开局已符合目标路线，直接沿用（未重刷）"
                    else "命中目标路线开局，总尝试次数：$lastAttempt",
                )
                AppState.setRerollBusy(false)
                true
            }
            is RerollResult.Failure -> {
                AppState.addLog("刷开局失败：${r.message}")
                AppState.setRerollStatus("失败：${r.message}")
                AppState.setRerollBusy(false)
                false
            }
        }
    }

    fun reset() {
        client = null
        session = null
        RerollState.clear()
        AppState.setRerollBusy(false)
        AppState.setRerollCaptcha(null)
        AppState.setRerollStatus(null)
        AppState.setRerollResultSummary(null)
    }
}
