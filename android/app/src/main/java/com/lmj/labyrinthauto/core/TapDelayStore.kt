package com.lmj.labyrinthauto.core

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 按钮「点早了」的自学习 —— 只记**附加等待**，不统计耗时。
 *
 * 用户 2026-10-27 定的规则（原话）：
 *   「不用统计每个按钮过多久可点，基本上基于一个尝试的思路，一次点早了，下次同样的按钮就稍微晚点。
 *     正常情况下，额外延迟上限给到 1200ms 已经足够了，再多就清空，而这种明确很久的页面可以一次
 *     +600ms，上限 3000ms。」
 *
 * 所以这里**没有**平均值/分位数，只有一个整数：`key -> extraMs`（默认 0 = 没学过，行为与以前完全一样）。
 *
 * 触发路径（见 `MazeLoop` 主循环的"点击探针"）：
 *   ① 点了一颗带 `learnKey` 的按钮；② 下一轮重新算出"现场 tag"；
 *   ③ tag 没变 ⇒ 认定"点早了（那会儿按钮还不可点）" ⇒ `miss()` 让这个 key 下次多等一点。
 *   tag 变了 ⇒ 什么都不做（成功不回调，避免自己把自己越学越慢）。
 *
 * 为什么用 `tag` 当判据：它是现成的"现场指纹"（节点+面板+状态+view+顶框），
 * 每轮本来就要算，**比较字符串零成本**，不用任何额外读取 —— 这点很重要：
 * 学延迟的目的是**减少**白点，不能为了学而增加开销。
 *
 * ⚠已知的误判面（用户已知并接受）：连着弹两个**同类同名**弹窗时，`tag` 里顶框相同 ⇒
 *   第二次会被当成"第一次没点动"而 +step。后果只是多等一点，不会点错；
 *   靠"上限清空"兜住（1000/1200 一档，或结算页 3000）。
 */
object TapDelayStore {

    private const val FILE = "ui_tap_delay.json"

    /** 内存缓存（读一次文件，改一次写一次；`extra()` 在每轮循环里被调用，不能每次读盘） */
    @Volatile
    private var cache: MutableMap<String, Long>? = null

    /** 该类按钮当前的附加等待（0 = 没学过） */
    @Synchronized
    fun extra(ctx: Context, key: String): Long = load(ctx)[key] ?: 0L

    /**
     * 记一次「点早了」：`extra += step`。
     * 超过 [cap] ⇒ **整条清空**（回 0，从头学）—— 用户规则："再多就清空"。
     * 返回新的值（0 表示刚被清空）。
     */
    @Synchronized
    fun miss(ctx: Context, key: String, step: Long, cap: Long): Long {
        val m = load(ctx)
        val next = (m[key] ?: 0L) + step
        if (next > cap) {
            m.remove(key)
            save(ctx, m)
            return 0L
        }
        m[key] = next
        save(ctx, m)
        return next
    }

    private fun load(ctx: Context): MutableMap<String, Long> {
        cache?.let { return it }
        val m = LinkedHashMap<String, Long>()
        runCatching {
            val f = File(ctx.filesDir, FILE)
            if (f.isFile) {
                val o = JSONObject(f.readText())
                o.keys().forEach { k ->
                    val v = o.optLong(k, 0L)
                    if (v > 0) m[k] = v
                }
            }
        }
        cache = m
        return m
    }

    private fun save(ctx: Context, m: MutableMap<String, Long>) {
        runCatching {
            val o = JSONObject()
            m.forEach { (k, v) -> o.put(k, v) }
            File(ctx.filesDir, FILE).writeText(o.toString())
        }
    }
}
