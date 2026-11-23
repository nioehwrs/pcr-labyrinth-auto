package com.lmj.labyrinthauto.logic.shop

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * ★**商店抽样日志**（2026-11-4 建立）：记录每次「看到一屏新货架」，供离线分析刷新规律。
 *
 * ★★**已验证结论**（2026-11-22，3 台机合并 662 次刷新 / 1986 件，见 `logs/_pull/shop_*.py`）：
 *  · **刷新不是各 1/3**：实测 **1★≈60% / 2★≈34% / 3★≈6%**（3★ 的 95%CI [4.9,7.0]%，排除 5%）；
 *  · **与「持有什么星级」基本无关**：控制总持有量后，持有 1★/3★ 的多少对刷新占比无显著影响
 *    （仅 2★ 有微弱负相关，来源是「已持有遗物不再进池」的排除效应，非概率被调整）；
 *  · **3 层 vs 5 层**：3 层约 62/32/6、5 层约 58/36/6（3★ 两层一致）；齐性检验 p=0.081 未过 0.05，
 *    拆层依据尚不充分，算法暂用合并口径。
 *  → 旧的 H_A(直接从剩余件抽) / H_B(先 1/3 定星级) 两假设均不成立，不再作为前提。
 *
 * 每次「看到一屏新货架」（进店 / 刷新后）记一条，落到
 * `/sdcard/Android/data/<pkg>/files/logs/shop_samples.jsonl`：
 * ```
 * {"ts":…,"key":"<run>-<blockId>-<refresh>","area":5,"refresh":0,
 *  "coin":12345,"shelf":[{"id":"31001","kind":"3★遗物","rarity":3,"sold":false},…],
 *  "owned":["40003","31001",…]}
 * ```
 * ★`owned` 记**全量 id**（含公会专属），池内/池外的划分留到分析阶段做 —— 免得在这里猜名单。
 */
object ShopSampleLog {

    private const val MAX_BYTES = 8L * 1024 * 1024

    /** 进程内去重：同一 (run, 货架, 刷新次数) 只记一次 */
    private val seen = HashSet<String>()

    fun shouldRecord(key: String): Boolean = synchronized(this) { key !in seen }

    /** 记录一屏货架；[key] 相同则忽略 */
    fun record(ctx: Context, key: String, json: JSONObject) {
        synchronized(this) {
            if (!seen.add(key)) return
        }
        runCatching {
            val dir = File(ctx.getExternalFilesDir(null), "logs")
            dir.mkdirs()
            val f = File(dir, "shop_samples.jsonl")
            if (f.length() > MAX_BYTES) {
                val old = File(dir, "shop_samples.old.jsonl")
                old.delete()
                f.renameTo(old)
            }
            json.put("key", key)
            f.appendText(json.toString() + "\n")
        }
    }

    /** 清空去重表（换局时调用，避免 HashSet 长期增长） */
    fun reset() = synchronized(this) { seen.clear() }

    /** 采样文件（可能不存在） */
    fun currentFile(ctx: Context): File =
        File(File(ctx.getExternalFilesDir(null), "logs"), "shop_samples.jsonl")

    /** 统计（给 UI 显示）：屏数 / 遗物件数 / 各星级件数 */
    data class Stats(
        val screens: Int = 0,
        val items: Int = 0,
        val c1: Int = 0,
        val c2: Int = 0,
        val c3: Int = 0,
        val bytes: Long = 0,
    ) {
        fun line(): String =
            if (items == 0) "还没有商店样本 —— 进一次商店就会自动记一屏"
            else {
                fun pct(x: Int) = if (items > 0) x * 100 / items else 0
                "$screens 屏 ｜ $items 件 ｜ 1★ $c1(${pct(c1)}%) 2★ $c2(${pct(c2)}%) 3★ $c3(${pct(c3)}%)"
            }
    }

    /** 读文件算统计（只统计遗物：rarity 1..3；印记 rarity=0 不计） */
    fun stats(ctx: Context): Stats {
        val f = currentFile(ctx)
        if (!f.exists()) return Stats()
        var screens = 0
        var items = 0
        var c1 = 0
        var c2 = 0
        var c3 = 0
        runCatching {
            f.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                // ★2026-11-11（用户）：**4 层商店整屏跳过** —— 它固定 6 件 3★ 专属件（no_area4_only），
                //   混进来会把看板的 3★ 从 ~5% 抬到 20% 以上，没有任何参考价值。
                if (o.optInt("area", 0) == 4) return@forEachLine
                screens++
                val arr = o.optJSONArray("shelf") ?: return@forEachLine
                for (i in 0 until arr.length()) {
                    val r = arr.optJSONObject(i)?.optInt("rarity") ?: 0
                    if (r !in 1..3) continue
                    items++
                    when (r) {
                        1 -> c1++
                        2 -> c2++
                        3 -> c3++
                    }
                }
            }
        }
        return Stats(screens, items, c1, c2, c3, f.length())
    }
}
