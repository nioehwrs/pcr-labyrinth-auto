package com.lmj.labyrinthauto.logic

import android.content.Context

/**
 * 编队页「角色一览」**一次滑动滚几行**（单位：行）。
 *
 * - 默认 1.5 行（与 PC `PARTY_SCROLL_ROWS` 同值）；范围夹紧 1.0~2.0。
 * - 可在「其他」设置页调整；若反复滑动仍点不到角色，把此值调小。
 * - 逐拍滑动的实际像素距离 = 此值 × 行距（行距由 [MazeLoop] 整局缓存反推，`scrollPartyList`）。
 */
object PartyScrollSettings {
    private const val PREF = "party_scroll"
    private const val KEY_ROWS = "rows"
    const val DEFAULT_ROWS = 1.5f

    /** 读取「每次滑动滚几行」，夹紧到 1.0~2.0。 */
    fun loadRows(context: Context): Float =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getFloat(KEY_ROWS, DEFAULT_ROWS).coerceIn(1.0f, 2.0f)

    /** 保存「每次滑动滚几行」，夹紧到 1.0~2.0。 */
    fun saveRows(context: Context, v: Float) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_ROWS, v.coerceIn(1.0f, 2.0f)).apply()
    }
}
