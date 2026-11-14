package com.lmj.labyrinthauto.labmodel

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.SystemClock
import com.lmj.labyrinthauto.core.AppState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * 黎明界编队「推理模型」（2026-11-14）—— 逐段对应决策系统 `src/inference.cpp` + `src/team.cpp`：
 *
 * | 这里 | C++ |
 * |---|---|
 * | [load] | `Engine::Engine`（manifest 校验、词表、三个 session） |
 * | [encode] | `Engine::encode`（quest **索引** + objective + owned_mask → memory） |
 * | [decode] | `Engine::decode`（memory + prefixes + step → logits） |
 * | [beam] | `Engine::generate_candidates`（组合少就全枚举，否则 Gumbel 束搜索） |
 * | [score] | `Engine::score`（critic 六分类：5 个时长箱 + 失败） |
 * | [ranked] | `Engine::ranked`（90% 胜率优先 → 耗时 → 胜率 → goal → 耗时 → id） |
 * | [planTeam] | `plan_team`（多队时每队从剩余池再算一次） |
 *
 * ★两条从 C++ 抄来的坑（实测踩过）：
 *   · `quest` 输入是**词表索引**（1..254），不是 questId —— 传 770110101 会 Gather 越界；
 *   · `objective_value` 是 0..3，对应 10/30/60/90 秒。
 *
 * 模型文件放在 `filesDir/../labmodel/`（预下载组件落位处）；缺失/加载失败时 [ready] 为 false，
 * 调用方应当**静默回落规则版**。
 */
object LabModel {

    private const val DIR = "labmodel"
    private const val TEAM_SIZE = 5
    private const val PREFIX_COLS = 4          // decoder 的 prefixes 固定 [B,4]
    private const val HIDDEN = 256
    private val OBJECTIVE_TIMES = intArrayOf(10, 30, 60, 90)

    /** critic 的时长分箱（v1：10/30/60/90 秒 + 失败）与其中点 */
    private val BOX_TIMES = intArrayOf(10, 30, 60, 90)
    private val BOX_MID = doubleArrayOf(5.0, 20.0, 45.0, 75.0)

    private val lock = Any()

    private var env: OrtEnvironment? = null
    private var ctxSession: OrtSession? = null
    private var decSession: OrtSession? = null
    private var crtSession: OrtSession? = null

    private var units = IntArray(0)
    private val unitIndex = HashMap<Int, Int>()
    private val questIndex = HashMap<Int, Int>()   // questId -> 1-based（decoder 用）
    private val criticIndex = HashMap<Int, Int>()  // questId -> 0-based（critic 用）
    private var layers = IntArray(0)
    private var enemies = emptyArray<IntArray>()
    private var priors = DoubleArray(0)
    /** ★候选数分开配（2026-11-14 用户）：编队要质量（64），选人要响应速度（32）。 */
    private var candidatesTeam = 64
    private var candidatesPick = 32
    private var seedBase = 20261101L

    /** 本机用于推理的线程数（按可用核数，最多 4）。 */
    private var inferThreads = 2

    /** 模型是否可用（加载成功后为 true；失败一次就不再重试，避免每局卡两秒）。 */
    @Volatile
    var ready: Boolean = false
        private set

    @Volatile
    var lastError: String = ""
        private set

    /** 加载模型（幂等；失败只记日志、不抛）。 */
    fun load(ctx: Context): Boolean {
        if (ready) return true
        synchronized(lock) {
            if (ready) return true
            if (lastError.isNotEmpty()) return false      // 已经失败过，别每局重试
            return try {
                doLoad(ctx)
                ready = true
                AppState.addLog("[模型] 已加载：角色词表 ${units.size}、关卡词表 ${questIndex.size}")
                true
            } catch (t: Throwable) {
                lastError = "${t.javaClass.simpleName}: ${t.message}"
                AppState.addLog("[模型] 加载失败（之后一律用规则版）：$lastError")
                closeQuietly()
                false
            }
        }
    }

    private fun doLoad(ctx: Context) {
        // ★统一读私有目录（组件下载落在这里）；外部目录那份只作兼容（老的手动 push）
        val dir = LabModelInstaller.dirOf(ctx)
            .takeIf { File(it, "metadata.json").isFile }
            ?: File(ctx.getExternalFilesDir(null), DIR)
        val metaFile = File(dir, "metadata.json")
        require(metaFile.exists()) { "缺少 metadata.json" }
        val meta = JSONObject(metaFile.readText())

        units = meta.getJSONArray("unit_ids").toIntArray()
        unitIndex.clear()
        units.forEachIndexed { i, u -> unitIndex[u] = i }
        require(units.size >= TEAM_SIZE) { "角色词表过小" }

        val quests = meta.getJSONArray("quest_ids").toIntArray()
        questIndex.clear()
        quests.forEachIndexed { i, q -> questIndex[q] = i + 1 }

        val cv = meta.optJSONObject("critic_vocabulary") ?: JSONObject()
        val cq = cv.optJSONArray("quest_ids")?.toIntArray() ?: quests
        criticIndex.clear()
        cq.forEachIndexed { i, q -> criticIndex[q] = i }

        layers = meta.getJSONArray("layers").toIntArray()
        priors = meta.getJSONArray("critic_win_prior").toDoubleArray()
        enemies = meta.getJSONArray("critic_enemies").let { arr ->
            Array(arr.length()) { i -> arr.getJSONArray(i).toIntArray() }
        }
        candidatesTeam = meta.optInt("default_candidates", 64).coerceIn(8, 128)
        candidatesPick = (candidatesTeam / 2).coerceAtLeast(8)
        seedBase = meta.optLong("seed", 20261101L)
        // ★线程数按可用核数（2026-11-14 用户）：模拟器一般给 4 核，用满比写死 2 快不少。
        inferThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

        val e = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            runCatching { setIntraOpNumThreads(inferThreads) }
            runCatching { setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT) }
        }
        env = e
        ctxSession = e.createSession(File(dir, "context.onnx").absolutePath, opts)
        decSession = e.createSession(File(dir, "decoder.onnx").absolutePath, opts)
        crtSession = e.createSession(File(dir, "critic.onnx").absolutePath, opts)
    }

    private fun closeQuietly() {
        runCatching { ctxSession?.close() }
        runCatching { decSession?.close() }
        runCatching { crtSession?.close() }
        ctxSession = null
        decSession = null
        crtSession = null
    }

    /** quest 是否在模型词表里（不在就说明这关模型没学过，直接回规则版）。 */
    fun supportsQuest(questId: Int): Boolean = questIndex.containsKey(questId)



    // ────────────────────────── 编队主入口 ──────────────────────────

    /**
     * @param owned 本局持有角色（未过滤）
     * @param partyCount 1 = 普通/EX；3 = 首领（按配置可能是 1）
     * @param questType 1=普通 2=EX 3=BOSS；**BOSS 用 90 秒目标，其余 30 秒**（C++ 同款）
     * @return 每队 5 个 unitId（按胜率从高到低）；null = 模型不可用/算不出，调用方回落规则版
     */
    fun planTeam(questId: Int, owned: List<Int>, partyCount: Int, questType: Int): List<List<Int>>? {
        if (!ready) return null
        val qi = questIndex[questId] ?: return null
        // 只留模型认识的角色（不认识的直接丢，C++ 同款）
        val known = owned.filter { unitIndex.containsKey(it) }.distinct().sorted()
        if (known.size < TEAM_SIZE) return null
        val seconds = if (questType == 3) 90 else 30
        val goal = OBJECTIVE_TIMES.indexOf(seconds).let { if (it >= 0) it else 1 }
        return try {
            val need = partyCount.coerceIn(1, 3)
            val teams = ArrayList<List<Int>>()
            var pool = known
            repeat(need) {
                val best = bestTeam(questId, qi, pool, goal, seconds) ?: return@repeat
                teams.add(best)
                pool = pool.filter { it !in best }
            }
            teams.ifEmpty { null }
        } catch (t: Throwable) {
            AppState.addLog("[模型] 编队失败（回落规则版）：${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /** 单个最优队伍：候选生成 → critic 打分 → 按 C++ `ranked` 的完整规则排序后取第一。 */
    private fun bestTeam(questId: Int, qi: Int, pool: List<Int>, goal: Int, seconds: Int): List<Int>? =
        bestScored(questId, qi, pool, goal, seconds, candidatesTeam, logTiming = true)?.team

    private fun bestScored(
        questId: Int,
        qi: Int,
        pool: List<Int>,
        goal: Int,
        seconds: Int,
        target: Int,
        logTiming: Boolean = false,
    ): Scored? {
        val t0 = SystemClock.elapsedRealtime()
        val cands = generateCandidates(questId, qi, pool, goal, target)
        if (cands.isEmpty()) return null
        val t1 = SystemClock.elapsedRealtime()
        val best = score(questId, cands, seconds)
            .sortedWith(Comparator { a, b -> if (before(a, b)) -1 else if (before(b, a)) 1 else 0 })
            .firstOrNull()
        if (logTiming && best != null) {
            AppState.addLog(
                "[模型] 编队耗时：生成 ${t1 - t0}ms（${cands.size} 个候选）+ " +
                    "打分 ${SystemClock.elapsedRealtime() - t1}ms（$inferThreads 线程）",
            )
        }
        return best
    }

    /**
     * 战后三选一：**多目标加权**（C++ `recruitment.cpp` 的 `gain` 同款思路）。
     *
     * 对每个候选 x：`gain(x) = Σ w_i·( win_i(池+x) − win_i(池) ) / Σ w_i`，
     * i 遍历 [targets]（本局 3/5 区首领 + 4 个 EX 关卡，权重见 [EX_TARGET_WEIGHTS]）。
     * 返回**卡片下标**；null = 算不出（调用方回落规则版）。
     */
    fun pickChar(
        candidates: List<Int>,
        owned: List<Int>,
        targets: List<Pair<Int, Double>>,
        questType: Int,
    ): Int? {
        if (!ready || targets.isEmpty()) return null
        val seconds = if (questType == 3) 90 else 30
        val goal = OBJECTIVE_TIMES.indexOf(seconds).let { if (it >= 0) it else 1 }
        val known = owned.filter { unitIndex.containsKey(it) }.distinct()
        if (known.size < TEAM_SIZE) return null
        val qs = targets.mapNotNull { (q, w) -> questIndex[q]?.let { Triple(q, it, w) } }
        if (qs.isEmpty()) return null

        val base = DoubleArray(qs.size)
        for (i in qs.indices) {
            base[i] = bestScored(qs[i].first, qs[i].second, known, goal, seconds, candidatesPick)?.win
                ?: return null
        }
        var bestIdx = -1
        var bestGain = Double.NEGATIVE_INFINITY
        for (i in candidates.indices) {
            val c = candidates[i]
            if (!unitIndex.containsKey(c)) continue
            var sum = 0.0
            var wsum = 0.0
            var ok = true
            for (t in qs.indices) {
                val (q, qi, w) = qs[t]
                val win = bestScored(q, qi, known + c, goal, seconds, candidatesPick)?.win
                if (win == null) { ok = false; break }
                sum += w * (win - base[t])
                wsum += w
            }
            if (!ok || wsum <= 0.0) continue
            val gain = sum / wsum
            if (gain > bestGain) {
                bestGain = gain
                bestIdx = i
            }
        }
        return if (bestIdx >= 0) bestIdx else null
    }


    /**
     * C++ `Engine::ranked` 的比较器（原样照抄）：
     * ① ≥90% 胜率优先；② 都 ≥90% 时比预计耗时（短的优先）；③ 比胜率；
     * ④ 比"目标时长内的胜率"；⑤ 再比耗时；⑥ 最后按队伍 ID 字典序稳定。
     */
    private fun before(a: Scored, b: Scored): Boolean {
        val qa = a.win >= 0.90
        val qb = b.win >= 0.90
        if (qa != qb) return qa
        if (qa && a.seconds != b.seconds) return a.seconds < b.seconds
        if (a.win != b.win) return a.win > b.win
        if (a.goal != b.goal) return a.goal > b.goal
        if (a.seconds != b.seconds) return a.seconds < b.seconds
        return a.team.joinToString(",") < b.team.joinToString(",")
    }

    /** 一支候选队 + critic 的三个派生量（C++ `struct Scored`）。 */
    private class Scored(
        val team: List<Int>,
        val win: Double,
        val goal: Double,
        val seconds: Double,
    )

    // ────────────────────────── 编码 / 前向 ──────────────────────────

    private class Encoded(val memory: FloatArray, val valid: ByteArray, val length: Int)

    private fun encode(qi: Int, goal: Int, mask: ByteArray): Encoded {
        val e = env!!
        val ctx = ctxSession!!
        val buf = ByteBuffer.allocateDirect(mask.size).order(ByteOrder.nativeOrder()).apply { put(mask); rewind() }
        OnnxTensor.createTensor(e, LongBuffer.wrap(longArrayOf(qi.toLong())), longArrayOf(1)).use { q ->
            OnnxTensor.createTensor(e, LongBuffer.wrap(longArrayOf(goal.toLong())), longArrayOf(1)).use { g ->
                OnnxTensor.createTensor(e, buf, longArrayOf(1, mask.size.toLong()), OnnxJavaType.BOOL).use { m ->
                    ctx.run(mapOf("quest" to q, "objective_value" to g, "owned_mask" to m)).use { out ->
                        val mem = out.get("memory").orElseThrow() as OnnxTensor
                        val val0 = out.get("memory_valid").orElseThrow() as OnnxTensor
                        val fb = mem.floatBuffer
                        val memory = FloatArray(fb.remaining()).also { fb.get(it) }
                        val vb = val0.byteBuffer
                        val valid = ByteArray(vb.remaining()).also { vb.get(it) }
                        val len = mem.info.shape[1].toInt()
                        return Encoded(memory, valid, len)
                    }
                }
            }
        }
    }

    /** decoder：memory + prefixes[B,4] + step → logits[B,314]（扁平） */
    private fun decode(enc: Encoded, prefixes: List<IntArray>): FloatArray {
        val e = env!!
        val dec = decSession!!
        val batch = prefixes.size
        val step = prefixes.firstOrNull()?.size ?: 0
        val padded = LongArray(batch * PREFIX_COLS)
        prefixes.forEachIndexed { b, p -> p.forEachIndexed { j, u -> padded[b * PREFIX_COLS + j] = u.toLong() } }
        val memBuf = FloatBuffer.wrap(enc.memory)
        val validBuf = ByteBuffer.allocateDirect(enc.valid.size).order(ByteOrder.nativeOrder()).apply {
            put(enc.valid); rewind()
        }
        OnnxTensor.createTensor(e, memBuf, longArrayOf(1, enc.length.toLong(), HIDDEN.toLong())).use { mt ->
            OnnxTensor.createTensor(
                e, validBuf, longArrayOf(1, enc.length.toLong()), OnnxJavaType.BOOL,
            ).use { vt ->
                OnnxTensor.createTensor(
                    e, LongBuffer.wrap(padded), longArrayOf(batch.toLong(), PREFIX_COLS.toLong()),
                ).use { pt ->
                    OnnxTensor.createTensor(e, LongBuffer.wrap(longArrayOf(step.toLong())), longArrayOf(1)).use { st ->
                        dec.run(mapOf("memory" to mt, "memory_valid" to vt, "prefixes" to pt, "step" to st))
                            .use { out ->
                                val lg = out.get("logits").orElseThrow() as OnnxTensor
                                val fb = lg.floatBuffer
                                return FloatArray(fb.remaining()).also { fb.get(it) }
                            }
                    }
                }
            }
        }
    }

    private fun critic(questId: Int, teams: List<List<Int>>, targetSeconds: Int): List<Scored> {
        val e = env!!
        val crt = crtSession!!
        val b = teams.size
        // ★critic 用的是 c_index（0-based），和 decoder 的 q_index（1-based）**不是同一套**：
        //   混用会让 layers/enemies/prior 全部错位 1，选出来的队和桌面版完全不同（踩过）。
        val ci = criticIndex[questId] ?: 0
        val qs = LongArray(b) { ci.toLong() }
        val ls = LongArray(b) { (layers.getOrNull(ci) ?: 1).toLong() }
        val en = LongArray(b * TEAM_SIZE)
        val tm = LongArray(b * TEAM_SIZE)
        val enRow = enemies.getOrNull(ci) ?: IntArray(0)
        for (i in 0 until b) {
            for (j in 0 until TEAM_SIZE) {
                en[i * TEAM_SIZE + j] = (enRow.getOrNull(j) ?: 0).toLong()
                tm[i * TEAM_SIZE + j] = (unitIndex[teams[i][j]] ?: 0).toLong() + 2
            }
        }
        OnnxTensor.createTensor(e, LongBuffer.wrap(qs), longArrayOf(b.toLong())).use { qt ->
            OnnxTensor.createTensor(e, LongBuffer.wrap(ls), longArrayOf(b.toLong())).use { lt ->
                OnnxTensor.createTensor(
                    e, LongBuffer.wrap(en), longArrayOf(b.toLong(), TEAM_SIZE.toLong()),
                ).use { et ->
                    OnnxTensor.createTensor(
                        e, LongBuffer.wrap(tm), longArrayOf(b.toLong(), TEAM_SIZE.toLong()),
                    ).use { tt ->
                        crt.run(mapOf("quests" to qt, "layers" to lt, "enemies" to et, "teams" to tt))
                            .use { out ->
                                val lg = out.get("logits").orElseThrow() as OnnxTensor
                                val fb = lg.floatBuffer
                                val raw = FloatArray(fb.remaining()).also { fb.get(it) }
                                // ★v1 是 **4 个时长箱（10/30/60/90）+ 1 个失败 = 5 类**；
                                //   v2（`relic_probabilities_v1`）才是 5+1=6。C++ 的 `win_bins` 同款。
                                val winBins = 4
                                val classes = winBins + 1
                                require(raw.size == b * classes) {
                                    "critic 输出 ${raw.size}，期望 ${b * classes}"
                                }
                                val prior = (priors.getOrNull(ci) ?: 0.5).coerceIn(1e-7, 1 - 1e-7)
                                val out = ArrayList<Scored>(b)
                                for (i in 0 until b) {
                                    val row = DoubleArray(classes) { j ->
                                        val v = raw[i * classes + j].toDouble()
                                        if (j < winBins) v + ln(prior) else v + ln(1 - prior)
                                    }
                                    val max = row.max()
                                    var sum = 0.0
                                    for (j in 0 until classes) { row[j] = exp(row[j] - max); sum += row[j] }
                                    var w = 0.0
                                    var s = 0.0
                                    var g = 0.0
                                    for (j in 0 until winBins) {
                                        val p = row[j] / sum
                                        w += p
                                        s += p * BOX_MID[j]
                                        if (BOX_TIMES[j] <= targetSeconds) g += p
                                    }
                                    val win = w.coerceIn(0.0, 1.0)
                                    out.add(Scored(teams[i], win, g, s / maxOf(win, 1e-12)))
                                }
                                return out
                            }
                    }
                }
            }
        }
    }

    private fun score(questId: Int, teams: List<List<Int>>, seconds: Int): List<Scored> =
        critic(questId, teams, seconds)

    // ────────────────────────── 候选生成 ──────────────────────────

    /** 组合数少于 candidates 就全枚举，否则 Gumbel 束搜索（C++ `generate_candidates`）。 */
    private fun generateCandidates(
        questId: Int,
        qi: Int,
        pool: List<Int>,
        goal: Int,
        target: Int,
    ): List<List<Int>> {
        val mask = ByteArray(units.size)
        pool.forEach { unitIndex[it]?.let { i -> mask[i] = 1 } }
        val avail = mask.count { it.toInt() == 1 }
        if (avail < TEAM_SIZE) return emptyList()
        val combos = combinations(avail, TEAM_SIZE)
        if (combos <= target || combos <= 0) {
            return enumerate(mask, combos.toInt())
        }
        val enc = encode(qi, goal, mask)
        // ★种子是 `candidate_seed + **questId**`（不是词表索引）；用错就整批候选都不同。
        val rnd = SplitMix64(seedBase + questId)
        var beams = listOf(Beam(IntArray(0), 0.0, 0.0))
        for (step in 0 until TEAM_SIZE) {
            val logits = decode(enc, beams.map { it.prefix })
            val ext = ArrayList<Ext>()
            for ((bi, parent) in beams.withIndex()) {
                val row = logits.copyOfRange(bi * units.size, (bi + 1) * units.size)
                val first = if (parent.prefix.isEmpty()) 0 else parent.prefix.last() + 1
                val slotsLeft = TEAM_SIZE - step - 1
                val legal = ArrayList<Int>()
                var maxLogit = Double.NEGATIVE_INFINITY
                for (u in first until units.size) {
                    if (mask[u].toInt() != 1) continue
                    // 后面还要填 slotsLeft 个 ⇒ 必须还有足够的更大索引可挑
                    if (slotsLeft > 0 && countAbove(mask, u) < slotsLeft) break
                    legal.add(u)
                    maxLogit = maxOf(maxLogit, row[u].toDouble())
                }
                if (legal.isEmpty()) break
                var sum = 0.0
                for (u in legal) sum += exp(row[u].toDouble() - maxLogit)
                val logNorm = ln(sum)
                val firstChild = ext.size
                var maximum = Double.NEGATIVE_INFINITY
                for (u in legal) {
                    val logp = parent.logp + (row[u].toDouble() - maxLogit) - logNorm
                    val pri = logp + rnd.gumbel()
                    maximum = maxOf(maximum, pri)
                    ext.add(Ext(bi, u, logp, pri))
                }
                // ★Kool et al. 2019 的条件重标定（C++ `condition_gumbel`）：漏了这段候选会跑偏。
                for (i in firstChild until ext.size) {
                    ext[i] = ext[i].copy(pri = conditionGumbel(ext[i].pri, maximum, parent.pri))
                }
            }
            if (ext.isEmpty()) break
            val kept = minOf(target, ext.size)
            val sorted = ext.sortedWith(
                compareByDescending<Ext> { it.pri }.thenBy { it.parent }.thenBy { it.unit },
            )
            beams = sorted.take(kept).map { e ->
                val p = IntArray(beams[e.parent].prefix.size + 1)
                beams[e.parent].prefix.copyInto(p)
                p[p.size - 1] = e.unit
                Beam(p, e.logp, e.pri)
            }
        }
        return beams.map { b -> b.prefix.map { units[it] }.sorted() }
    }

    private data class Beam(val prefix: IntArray, val logp: Double, val pri: Double)

    private data class Ext(val parent: Int, val unit: Int, val logp: Double, val pri: Double)

    private fun countAbove(mask: ByteArray, from: Int): Int {
        var n = 0
        for (i in from + 1 until mask.size) if (mask[i].toInt() == 1) n++
        return n
    }

    private fun combinations(n: Int, k: Int): Long {
        if (k > n) return 0
        var r = 1L
        for (i in 1..k) r = r * (n - k + i) / i
        return r
    }

    /** 从 mask 里枚举所有 5 人组合（顺序同 C++：字典序递增）。 */
    private fun enumerate(mask: ByteArray, limit: Int): List<List<Int>> {
        val roster = units.filterIndexed { i, _ -> mask[i].toInt() == 1 }
        val out = ArrayList<List<Int>>(minOf(limit, 4096))
        val idx = IntArray(TEAM_SIZE) { it }
        while (true) {
            out.add(idx.map { roster[it] })
            if (out.size >= limit) break
            var i = TEAM_SIZE - 1
            while (i >= 0 && idx[i] == roster.size - TEAM_SIZE + i) i--
            if (i < 0) break
            idx[i]++
            for (j in i + 1 until TEAM_SIZE) idx[j] = idx[j - 1] + 1
        }
        return out
    }

    /** C++ `struct Random`：SplitMix64 + Gumbel。逐位照搬，换个 RNG 结果就完全不同。 */
    private class SplitMix64(private var state: Long) {
        fun gumbel(): Double {
            state += GOLDEN
            var z = state
            z = (z xor (z ushr 30)) * C1
            z = (z xor (z ushr 27)) * C2
            val x = ((z xor (z ushr 31)) ushr 12).toDouble()
            val uniform = (x + 0.5) * TWO_POW_NEG_52
            return -ln(-ln(uniform))
        }

        companion object {
            /** 0x9e3779b97f4a7c15 等的补码写法（Kotlin 的 Long 字面量放不下无符号值） */
            private const val GOLDEN = -0x61c8864680b583ebL
            private const val C1 = -0x40a7b892e31b1a47L
            private const val C2 = -0x6b2fb644ecceee15L
            private const val TWO_POW_NEG_52 = 1.0 / 4503599627370496.0
        }
    }

    /** C++ `condition_gumbel`（Kool et al. 2019, Algorithm 1）。 */
    private fun conditionGumbel(sampled: Double, maximum: Double, parent: Double): Double {
        if (sampled == maximum) return parent
        val difference = parent - sampled + ln(-Math.expm1(sampled - maximum))
        val softplus = maxOf(0.0, difference) + Math.log1p(exp(-abs(difference)))
        return parent - softplus
    }

    private fun JSONArray.toIntArray(): IntArray = IntArray(length()) { getInt(it) }

    private fun JSONArray.toDoubleArray(): DoubleArray = DoubleArray(length()) { getDouble(it) }
}
