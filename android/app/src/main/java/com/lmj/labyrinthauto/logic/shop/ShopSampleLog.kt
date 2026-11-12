package com.lmj.labyrinthauto.logic.shop

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * ★**商店抽样日志**（2026-11-4 用户需求）：验证「刷新出 1/2/3★ 的概率是否真是各 1/3」。
 *
 * 背景（用户给定的前提，非假设）：
 *  · 遗物共 **75 件**，1/2/3 星各 25 件；
 *  · **已经到手的不再进随机池**（不放回）；
 *  · 每个公会另有 2 件**专属**初始遗物，**本来就不在池里**（分析时要剔除）；
 *  · 未知的是：**「某星级池子变少」会不会改变该星级的到达概率** ——
 *       H_B：先 1/3 定星级再抽件 ⇒ `p_r` 恒为 1/3（与持有无关）
 *       H_A：直接从剩余件里抽     ⇒ `p_r = (25−h_r)/(75−h)`
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
