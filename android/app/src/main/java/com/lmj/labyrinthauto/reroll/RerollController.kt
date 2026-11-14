package com.lmj.labyrinthauto.reroll

import com.lmj.labyrinthauto.net.labyrinth.LabyrinthApi
import com.lmj.labyrinthauto.net.labyrinth.LabyrinthEnterRequest
import com.lmj.labyrinthauto.net.labyrinth.LabyrinthMapNode
import com.lmj.labyrinthauto.net.labyrinth.LabyrinthOperationResult
import com.lmj.labyrinthauto.net.labyrinth.LabyrinthResume
import com.lmj.labyrinthauto.net.labyrinth.LabyrinthTop
import kotlinx.coroutines.delay

sealed interface RerollResult {
    data class Success(
        val enterId: Long,
        val guildId: Int,
        val difficulty: Int,
        val nodes: List<LabyrinthMapNode>,
        val bossByArea: Map<Int, Int>,
        /** ★每区 Boss 的**实战 questId**（2026-11-14）：三选一「多目标加权」要用模型认的编号。 */
        val bossQuestByArea: Map<Int, Int> = emptyMap(),
        val routePerArea: Map<Int, List<Int>>,
        val actual: Int,
        val theory: Int,
        /** 一句话摘要：经典=`价值=5690/5690`、自定义=`节点顺序=5/5`（空 = 老逻辑按 actual/theory 拼） */
        val summary: String = "",
        /** ★true = **当前已有开局就合格 ⇒ 直接沿用、一次都没重刷**（PC `reused` 同款，2026-10-16 加）。
         *  以前没有这个标记 ⇒ 沿用和重刷打同一句"刷到合格开局"，日志看起来像重刷过（用户指出）。 */
        val reused: Boolean = false,
    ) : RerollResult

    data class Failure(val message: String) : RerollResult
}

data class RerollProgress(
    val attempt: Int,
    val total: Int,
    val stage: String,
    val lastFail: String?,
)

/**
 * 刷开局主循环（移植自 PC reroll.py `ensure_opening`）：
 *  1. 读顶部：若已有未撤离开局 → 先看它的**公会 / 难度**是否与配置一致，再 resume 判定
 *     Boss 白名单 + 价值偏差；全过才沿用（不撤退、不重刷），任一项不符就 retire；
 *  2. 循环 enter/retire，直到判定合格或达上限；
 *  3. 循环至判定合格或达上限（无"同因卡死"保护，按用户要求已移除）。
 */
class RerollController(
    private var api: LabyrinthApi,
    private val config: RerollConfig,
    private val onProgress: suspend (RerollProgress) -> Unit,
    /** ★true = 上一局被「撤退重跑」作废 ⇒ **即使当前局合格也不沿用**，直接废掉重 enter
     *  （2026-10-19 修：作废局的路线本身就合格 ⇒ 没有这个标记，下一局会"直接沿用"打不过的废局 → 死循环） */
    private val skipResume: Boolean = false,
    /**
     * ★连续 enter 报错、退避升到 16s 时调用一次：重新登录并用新会话返回新 api（null = 重登失败）。
     * 会话被挤掉（客户端冷启动抢登录 / 异常被踢）时，光退避永远不会恢复，只能换一张新会话。
     */
    private val onRelogin: (suspend () -> LabyrinthApi?)? = null,
) {
    suspend fun run(): RerollResult {
        var top = when (val r = api.top()) {
            is LabyrinthOperationResult.Success -> r.value
            is LabyrinthOperationResult.Failure ->
                return RerollResult.Failure("读取黎明界顶部信息失败：${r.message}")
        }
        // ★撤退重跑的下一局：把废局撤掉再走正常流程（此时游戏进程已杀，API 不会被拒）
        if (skipResume && top.enterId != null) {
            onProgress(RerollProgress(0, 0, "撤退重跑：上一局已作废 → 不沿用，直接废掉重刷", null))
            api.retire(top.enterId!!)
            top = when (val r = api.top()) {
                is LabyrinthOperationResult.Success -> r.value
                is LabyrinthOperationResult.Failure ->
                    return RerollResult.Failure("读取黎明界顶部信息失败：${r.message}")
            }
        }

        val maxUnlocked = maxUnlockedDifficulty(top)
        var difficulty = config.difficulty
        if (difficulty > maxUnlocked) {
            onProgress(RerollProgress(0, 0, "目标难度 $difficulty 未解锁，改用 $maxUnlocked", null))
            difficulty = maxUnlocked
        }
        val guild = config.guildId

        if (top.enterId != null) {
            val eid = top.enterId!!                     // ★var top 之后 smart cast 失效，先落局部
            onProgress(RerollProgress(0, 0, "读取当前已有开局…", null))
            when (val r = api.resume(eid)) {
                is LabyrinthOperationResult.Success -> {
                    val resume = r.value as LabyrinthResume
                    val j = RerollJudge.judge(resume.map, config)
                    // ★已有开局的「公会 / 难度」也必须与配置一致（2026-10-18 用户："公会和难度。加一下吧"）：
                    //   原来只判 Boss 白名单 + 价值偏差 ⇒ 哪怕开着的是别的公会/别的难度也会直接沿用，
                    //   跑完才发现"不是我要的那家"。读不到（null）时不拦，避免误撤退。
                    val curGuild = top.guildId ?: resume.guildId
                    val curDiff = top.difficulty
                    val guildOk = curGuild == null || curGuild == guild
                    val diffOk = curDiff == null || curDiff == difficulty
                    if (j.ok && guildOk && diffOk) {
                        return RerollResult.Success(
                            enterId = eid,
                            guildId = curGuild ?: guild,
                            difficulty = curDiff ?: difficulty,
                            nodes = resume.map,
                            bossByArea = j.bossByArea,
bossQuestByArea = j.bossQuestByArea,
                            routePerArea = j.routePerArea,
                            actual = j.actual,
                            theory = j.theory,
                            summary = j.summary,
                            reused = true,                      // ★沿用：没撤退、没重刷
                        )
                    }
                    if (!guildOk || !diffOk) {
                        val why = buildList {
                            if (!guildOk) add("公会 $curGuild ≠ $guild")
                            if (!diffOk) add("难度 $curDiff ≠ $difficulty")
                        }.joinToString("、")
                        onProgress(RerollProgress(0, 0, "当前开局$why → 撤退重刷", null))
                    }
                    api.retire(eid)
                }
                is LabyrinthOperationResult.Failure -> Unit
            }
        }

        val nMax = if (config.maxCount <= 0) Int.MAX_VALUE else config.maxCount
        var attempt = 0
        var errStreak = 0

        while (true) {
            attempt++
            if (attempt > nMax) {
                return RerollResult.Failure("重开 $nMax 次仍未刷到合格开局（放宽 max_value_gap / Boss 白名单，或把 max_count 设成 ≤0 = 不限次数）")
            }

            val enter = when (val r = api.enter(LabyrinthEnterRequest(guild, difficulty))) {
                is LabyrinthOperationResult.Success -> r.value
                is LabyrinthOperationResult.Failure -> {
                    errStreak++
                    // ★2026-11-13（用户）：enter 连续报错改成**指数退避**，不再连 5 次就结束整轮 ——
                    //   刷太快时服务端会回「发生了错误。回到标题界面。」，退避几秒通常就能恢复；
                    //   只要有一次 enter 成功就清零（见下面 `errStreak = 0`），从头算。
                    //   总次数上限仍由 maxCount 控制（<=0 = 不限次数，沿用用户定的口径）。
                    //   退避序列：2s、4s、8s、16s、32s、60s（封顶）。
                    val backoffMs = (2_000L shl (errStreak - 1).coerceAtMost(5)).coerceAtMost(60_000L)
                    // ★退避到 16s 及以后（16/32/60s）**每次等待前都重新登录**（2026-11-14 用户；
                    //   原来是"每条失败链只重登一次"）。会话被挤掉/过期时光退避永远等不回来；
                    //   重登成功 ⇒ 换新 api、退避链从头算（不额外等待）；失败 ⇒ 按原退避继续。
                    if (backoffMs >= 16_000L && onRelogin != null) {
                        onProgress(
                            RerollProgress(
                                attempt, nMax,
                                "enter 连续 $errStreak 次失败 → 重新登录（${backoffMs / 1000}s 档）",
                                null,
                            ),
                        )
                        val newApi = onRelogin.invoke()
                        if (newApi != null) {
                            api = newApi
                            errStreak = 0
                            onProgress(RerollProgress(attempt, nMax, "重新登录成功 → 继续刷开局", null))
                            continue
                        }
                    }
                    onProgress(
                        RerollProgress(
                            attempt,
                            nMax,
                            "enter 失败（连续 $errStreak 次，${r.kind}）：${r.message} → ${backoffMs / 1000}s 后重试",
                            null,
                        ),
                    )
                    delay(backoffMs)
                    continue
                }
            }
            errStreak = 0

            val j = RerollJudge.judge(enter.map, config)
            onProgress(
                RerollProgress(
                    attempt,
                    nMax,
                    if (j.ok) "命中目标路线开局" else "不满足要求",
                    j.fail.joinToString("；"),
                ),
            )

            if (j.ok) {
                return RerollResult.Success(
                    enterId = enter.enterId,
                    guildId = guild,
                    difficulty = difficulty,
                    nodes = enter.map,
                    bossByArea = j.bossByArea,
bossQuestByArea = j.bossQuestByArea,
                    routePerArea = j.routePerArea,
                    actual = j.actual,
                    theory = j.theory,
                    summary = j.summary,
                )
            }

            api.retire(enter.enterId)
            delay(config.sleepBetweenMs)
        }
    }

    private fun maxUnlockedDifficulty(top: LabyrinthTop): Int {
        val cleared = top.clearedDifficulties.mapNotNull { it.difficulty }
        val max = if (cleared.isEmpty()) 1 else (cleared.maxOrNull()!! + 1)
        return max.coerceAtMost(5)
    }
}
