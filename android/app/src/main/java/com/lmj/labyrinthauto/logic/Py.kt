package com.lmj.labyrinthauto.logic

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Python 语义的小工具 —— **只为"人看的字符串逐字一致"**而存在。
 *
 * 为什么需要：P4 的验收是"Kotlin 输出与 Python 完全一致"，而 PC 版把这些值直接拼进**说明文字/日志**
 * （`str(list)` / `%s` / `%+d` / `%.3f` / `round()`），Kotlin 的默认写法与 Python 并不相同：
 *
 * | 写法 | Kotlin 默认 | Python |
 * |---|---|---|
 * | 列表 | `listOf("a","b").toString()` → `[a, b]` | `str(['a','b'])` → `['a', 'b']` |
 * | 取整 | `Math.round(2.5)` → `3` | `round(2.5)` → `2`（银行家舍入） |
 * | 三位小数 | `String.format("%.3f", 0.0625)` → `0.063` | `"%.3f" % 0.0625` → `0.062` |
 * | 缺值 | `null` → `null` | `None` → `None` |
 *
 * 实现上统一走 `BigDecimal(精确二进制值)` + `HALF_EVEN`，与 CPython 的 decimal 舍入一致。
 */
internal object Py {

    /** Python 真值判断：None/False/0/""/空容器 都是假 */
    fun truthy(v: Any?): Boolean = when (v) {
        null, JSONObject.NULL -> false
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> v.isNotEmpty()
        is Collection<*> -> v.isNotEmpty()
        is Map<*, *> -> v.isNotEmpty()
        else -> true
    }

    /** `obj.get(key)` 当数字用（不是数字/缺失 → null） */
    fun num(o: JSONObject?, key: String): Double? = (o?.opt(key) as? Number)?.toDouble()

    /**
     * `int(x or 0)` 的语义：假值（缺失/None/0/""/false）→ 0；数值/数字串 → 截断成整数；
     * **转不动的（如 "abc"）→ null**（PC 版靠这个去触发 except 分支）。
     */
    fun intAfterOr0(v: Any?): Int? = when (v) {
        null, JSONObject.NULL -> 0
        is Number -> v.toInt()
        is Boolean -> if (v) 1 else 0
        is String -> if (v.isEmpty()) 0 else v.trim().toIntOrNull()
        else -> null
    }

    /** `str(x)`：给 `%s` 用（None → "None"，bool → True/False，数字按 Python 的写法） */
    fun str(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "None"
        is Boolean -> if (v) "True" else "False"
        else -> v.toString()
    }

    /** `str(list)`：`['1★遗物', '随机印记']` / `[3100, 4200]` */
    fun listStr(v: List<*>): String = v.joinToString(", ", "[", "]") { e ->
        if (e is String) "'$e'" else str(e)
    }

    /** `round(x)`（银行家舍入，返回整数） */
    fun roundInt(v: Double): Long =
        BigDecimal(v).setScale(0, RoundingMode.HALF_EVEN).toLong()

    /** `"%.3f" % x` */
    fun fmt3(v: Double): String =
        BigDecimal(v).setScale(3, RoundingMode.HALF_EVEN).toPlainString()

    /** `"%.2f" % x` */
    fun fmt2(v: Double): String =
        BigDecimal(v).setScale(2, RoundingMode.HALF_EVEN).toPlainString()

    /** `round(x, 1)` */
    fun round1(v: Double): Double =
        BigDecimal(v).setScale(1, RoundingMode.HALF_EVEN).toDouble()
}
