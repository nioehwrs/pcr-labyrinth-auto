package com.lmj.labyrinthauto

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 对拍测试的公共小工具：把 Kotlin 值 / JSON 值统一成**可比字符串**（键排序、整数不带小数点）。
 * 放在单独文件里，`TeamPortProbeTest`（第①层）和 `TeamPortFullTest`（第②层）共用。
 */
internal fun canon(v: Any?): String {
    if (v == null || v === JSONObject.NULL) return "null"
    return when (v) {
        is JSONObject -> {
            val ks = v.keys().asSequence().toList().sorted()
            ks.joinToString(",", "{", "}") { k -> q(k) + ":" + canon(v.opt(k)) }
        }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canon(v.opt(it)) }
        is Map<*, *> -> v.entries.map { it.key.toString() to it.value }.sortedBy { it.first }
            .joinToString(",", "{", "}") { q(it.first) + ":" + canon(it.second) }
        is Collection<*> -> v.joinToString(",", "[", "]") { canon(it) }
        is Boolean -> v.toString()
        is Number -> {
            val d = v.toDouble()
            if (d.isFinite() && d == Math.floor(d)) d.toLong().toString() else d.toString()
        }
        else -> q(v.toString())
    }
}

internal fun q(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

internal fun strList(a: JSONArray): List<String> = (0 until a.length()).map { a.optString(it) }

/** 从工作目录往上找"工程根"（认 tests/fixtures 这个路标），免得依赖 gradle 的工作目录约定。 */
internal fun findRepoRoot(): File {
    var d: File? = File(".").absoluteFile
    repeat(8) {
        val cur = d ?: return@repeat
        if (File(cur, "tests/fixtures/team_probes.json").exists()) return cur
        d = cur.parentFile
    }
    error("找不到工程根（要在 labyrinth_auto_android 里跑测试）")
}
