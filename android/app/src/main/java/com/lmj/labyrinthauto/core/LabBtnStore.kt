package com.lmj.labyrinthauto.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * ★**labBtn 固定路径表**（落盘 `files/ui_btn_paths.json`）：`key → (root, path)`。
 *
 * 背景（2026-10-20 用户："学了运行结束就没了，没价值"）：agent 的 `labBtn` 路径缓存只活在
 * **游戏进程**里，而我们每局都会杀游戏重启 ⇒ 每局都白学一遍（~1.5-2s/次）。其实 UI 层级在
 * 同一个游戏版本内是固定的：第一次学完把 (rootKind, path) 落盘，之后（含 app 重启）把路径
 * **回喂**给 agent（`labBtn`/`labQuestDetail` 的参数），沿父子索引链直达，完全不用再学；
 * 只有节点名校验失败（游戏更新/页面重构）才作废重学覆盖。
 *
 * key 建议带页面上下文（如 `"<pageSig>|<按钮名>"`），共享这张表的调用方：
 * - [com.lmj.labyrinthauto.reroll.InGameReroll]（局内刷开局的按钮）
 * - [com.lmj.labyrinthauto.logic.MazeLoop]（预览页「挑战」按钮，key = `QDStart|<按钮名>`）
 */
object LabBtnStore {
    private const val FILE = "ui_btn_paths.json"

    @Volatile
    private var map: MutableMap<String, Pair<String, List<Int>>>? = null

    private fun store(ctx: Context): MutableMap<String, Pair<String, List<Int>>> {
        map?.let { return it }
        val m = HashMap<String, Pair<String, List<Int>>>()
        runCatching {
            val f = java.io.File(ctx.filesDir, FILE)
            if (f.exists()) {
                val o = JSONObject(f.readText())
                val ks = o.keys()
                while (ks.hasNext()) {
                    val k = ks.next()
                    val e = o.optJSONObject(k) ?: continue
                    val arr = e.optJSONArray("path") ?: continue
                    val p = ArrayList<Int>()
                    for (i in 0 until arr.length()) p.add(arr.optInt(i))
                    m[k] = e.optString("root") to p
                }
            }
        }
        map = m
        return m
    }

    fun get(ctx: Context, key: String): Pair<String, List<Int>>? = store(ctx)[key]

    /** 写入并立即落盘（学习成功时调用；写盘失败静默——下次顶多重学一次） */
    fun put(ctx: Context, key: String, root: String, path: List<Int>) {
        store(ctx)[key] = root to path
        persist(ctx)
    }

    // ── ★弹窗按钮链（[{n,i}] 逐级"名字+索引"）────
    // 与上面的纯索引路径分开存（key 前缀 "chain|"）：弹窗链每级都校验名字+active，格式不同。
    // topKey 形如 "dlg|DialogCommonFullSize"（弹窗规范名，Kotlin 侧去 (Clone) 后缀）。

    private fun chainFile(ctx: Context) = java.io.File(ctx.filesDir, "ui_dlg_chains.json")

    @Volatile
    private var chainMap: MutableMap<String, JSONArray>? = null

    private fun chains(ctx: Context): MutableMap<String, JSONArray> {
        chainMap?.let { return it }
        val m = HashMap<String, JSONArray>()
        runCatching {
            val f = chainFile(ctx)
            if (f.exists()) {
                val o = JSONObject(f.readText())
                val ks = o.keys()
                while (ks.hasNext()) {
                    val k = ks.next()
                    o.optJSONArray(k)?.let { m[k] = it }
                }
            }
        }
        chainMap = m
        return m
    }

    /** 取某弹窗下学到的全部按钮链（回喂给 agent `dialog` RPC 的第 2 参） */
    fun getChains(ctx: Context, topKey: String): JSONArray {
        val out = JSONArray()
        val arr = chains(ctx)[topKey] ?: return out
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.put(it) }
        return out
    }

    /** 合并落盘一批新学到的弹窗按钮链（按 dlg+name 去重，同 key 覆盖） */
    fun putChains(ctx: Context, topKey: String, items: JSONArray) {
        if (items.length() == 0) return
        val m = chains(ctx)
        val cur = m[topKey] ?: JSONArray().also { m[topKey] = it }
        for (i in 0 until items.length()) {
            val it2 = items.optJSONObject(i) ?: continue
            val dlg = it2.optString("dlg")
            val name = it2.optString("name")
            if (dlg.isEmpty() || name.isEmpty()) continue
            val nk = "$dlg|$name"
            var replaced = false
            for (j in 0 until cur.length()) {
                val o = cur.optJSONObject(j) ?: continue
                if ("${o.optString("dlg")}|${o.optString("name")}" == nk) {
                    cur.put(j, it2); replaced = true; break
                }
            }
            if (!replaced) cur.put(it2)
        }
        runCatching {
            val o = JSONObject()
            chains(ctx).forEach { (k, v) -> o.put(k, v) }
            chainFile(ctx).writeText(o.toString())
        }
    }

    private fun persist(ctx: Context) {
        runCatching {
            val o = JSONObject()
            store(ctx).forEach { (k, v) ->
                o.put(k, JSONObject().put("root", v.first).put("path", JSONArray(v.second)))
            }
            java.io.File(ctx.filesDir, FILE).writeText(o.toString())
        }
    }

    // ── ★坐标缓存（2026-10-20）────
    // 背景：预览页「挑战」、FullSize 结算 OneButtonWhite 这类按钮不在任何可 descend 的根里
    //（canvas 根爬取失败 → qdDiag=no-canvas-root），但 BtnProbe 实测它们坐标基本不动（Δmax=0px）。
    // ⇒ 改存坐标 + 屏幕尺寸：命中（分辨率一致）直接用，点错由上层"点了看状态变化重试"兜底；
    //   分辨率/模拟器一变缓存自动失效（重新用 find 学一次坐标）。

    private fun xyFile(ctx: Context) = java.io.File(ctx.filesDir, "ui_btn_xy.json")

    @Volatile
    private var xyMap: MutableMap<String, IntArray>? = null

    private fun xys(ctx: Context): MutableMap<String, IntArray> {
        xyMap?.let { return it }
        val m = HashMap<String, IntArray>()
        runCatching {
            val f = xyFile(ctx)
            if (f.exists()) {
                val o = JSONObject(f.readText())
                val ks = o.keys()
                while (ks.hasNext()) {
                    val k = ks.next()
                    val a = o.optJSONArray(k) ?: continue
                    if (a.length() == 4) m[k] = intArrayOf(a.optInt(0), a.optInt(1), a.optInt(2), a.optInt(3))
                }
            }
        }
        xyMap = m
        return m
    }

    /** 取缓存坐标；屏幕尺寸不一致（分辨率变了）返回 null 自动失效 */
    fun getXY(ctx: Context, key: String, w: Int, h: Int): Pair<Int, Int>? {
        val a = xys(ctx)[key] ?: return null
        if (a[2] != w || a[3] != h) return null
        return a[0] to a[1]
    }

    fun putXY(ctx: Context, key: String, x: Int, y: Int, w: Int, h: Int) {
        xys(ctx)[key] = intArrayOf(x, y, w, h)
        runCatching {
            val o = JSONObject()
            xys(ctx).forEach { (k, a) -> o.put(k, JSONArray(listOf(a[0], a[1], a[2], a[3]))) }
            xyFile(ctx).writeText(o.toString())
        }
    }
}
