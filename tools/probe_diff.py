#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
P2 对拍（比对）：把 APK 侧与 PC 侧的 `PROBE <fn> <json>` 输出逐条比。

用法：
    python tools/probe_diff.py --app app_probe.txt --pc pc_probe.txt

比对规则（有意为之，逐条都写清楚）：
  * **忽略** `_ms` 字段：那是 agent 自己的耗时统计，每调必变，不是业务内容；
  * **忽略** `scanned` 字段 / `why` 里的「堆扫描=<n>」：堆扫描命中的对象数每次不同，是诊断噪声；
  * **忽略** `found` 字段（仅 `labParty` 有）：它是"agent 怎么定位到编组页"的诊断记录，命中缓存时
    为 null、走查找时才填——取决于 agent 内部缓存状态而非游戏状态，且真实"定位失败"会从 `abs`/
    `buttons`/`dict` 等字段的差异里暴露出来，故不参与比对；
  * **指针归一化**：形如 `0x7b9e6ef19c40` 的地址每次都不同，统一折成 `<ptr>`；
  * 数字统一按 5 位小数比较（`1` 与 `1.0` 不算差异）；
  * 布尔当布尔比（Python 里 True==1，这里显式区分，免得真把 bool 和 int 混过去）；
  * 调用失败（`{"__call_failed__": …}`）单独报，不和"真的返回 null"混。

★**状态指纹守卫**：对拍的前提是"两侧在同一页面、同一时刻取样"。本脚本会先比 `viewId`
（必要时含 `dialog.open`）——若两侧指纹不同，说明**不是同一个状态**，此时那堆"不一致"基本全是
状态差，不代表代码有差异。命中时会**在开头大声告警**，并把它算作一次失败（退出码 1）。

退出码：0 = 全部一致；1 = 有差异（含状态指纹不一致）；2 = 输入有问题。
"""
import argparse
import json
import re
import sys

PROBE_RE = re.compile(r"PROBE\s+(\w+)\s+(.*)$")
HEXPTR_RE = re.compile(r"0x[0-9a-fA-F]{4,}")
SCANCNT_RE = re.compile(r"堆扫描=\d+")
IGNORE_KEYS = {"_ms", "scanned", "found"}

# 参与"状态指纹"的字段：它们能唯一指示"当前在哪一页"
FP_KEYS = ["viewId"]


def load(path):
    """从文件里抓出 PROBE 行 -> {fn: parsed_json_or_None}（同一 fn 多次出现时保留最后一条）"""
    out = {}
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            for raw in f:
                m = PROBE_RE.search(raw)
                if not m:
                    continue
                fn, payload = m.group(1), m.group(2).strip()
                try:
                    out[fn] = json.loads(payload)
                except Exception as e:
                    out[fn] = {"__parse_error__": "%s: %s" % (e, payload[:120])}
    except FileNotFoundError:
        print("读不到文件：%s" % path, file=sys.stderr)
        sys.exit(2)
    return out


def norm(v, depth=0):
    """归一化：丢 _ms/scanned、指针折 <ptr>、布尔加标记、数字固定 5 位"""
    if isinstance(v, dict):
        return {k: norm(x, depth + 1) for k, x in sorted(v.items()) if k not in IGNORE_KEYS}
    if isinstance(v, list):
        return [norm(x, depth + 1) for x in v]
    if isinstance(v, bool):
        return "bool:%s" % v
    if isinstance(v, (int, float)):
        return "num:%.5f" % float(v)
    if v is None:
        return None
    if isinstance(v, str):
        s = HEXPTR_RE.sub("<ptr>", v)
        return SCANCNT_RE.sub("堆扫描=<n>", s)
    return v


def fingerprint(rows):
    """从归一化后的 PROBE 行里抽状态指纹。"""
    return {k: json.dumps(norm(rows.get(k)), ensure_ascii=False) for k in FP_KEYS}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--app", required=True, help="APK 侧 PROBE 输出")
    ap.add_argument("--pc", required=True, help="PC 侧 PROBE 输出")
    args = ap.parse_args()

    a = load(args.app)
    p = load(args.pc)
    if not a or not p:
        print("某一侧没抓到 PROBE 行（app=%d 条, pc=%d 条）" % (len(a), len(p)), file=sys.stderr)
        return 2

    # ── 状态指纹守卫 ──
    fa, fp = fingerprint(a), fingerprint(p)
    same_state = (fa == fp)
    print("状态指纹：APK %s ｜ PC %s" % (fa, fp))
    if not same_state:
        print("!" * 72)
        print("[!!] 两侧状态不同（viewId 不一致）——这不是代码差异，是**两次探针取样的页面不同**。")
        print("[!!] 本次对拍结果无效：请把游戏停在**同一个**稳定页面（如某个弹窗），连跑两侧再比。")
        print("!" * 72)

    fns = [fn for fn in p.keys()]
    only_app = [fn for fn in a if fn not in p]
    only_pc = [fn for fn in p if fn not in a]

    same, diff, failed = 0, 0, 0
    for fn in fns:
        if fn not in a:
            continue
        av, pv = a[fn], p[fn]
        if isinstance(av, dict) and "__call_failed__" in av:
            failed += 1
            print("[×] %-18s APK 侧调用失败：%s" % (fn, str(av)[:150]))
            continue
        if isinstance(pv, dict) and "__call_failed__" in pv:
            failed += 1
            print("[×] %-18s PC  侧调用失败：%s" % (fn, str(pv)[:150]))
            continue
        an, pn = norm(av), norm(pv)
        if an == pn:
            same += 1
            print("[=] %-18s 一致" % fn)
        else:
            diff += 1
            print("[!] %-18s 不一致" % fn)
            print("      APK: %s" % json.dumps(an, ensure_ascii=False)[:400])
            print("      PC : %s" % json.dumps(pn, ensure_ascii=False)[:400])

    if only_app:
        print("[?] 只有 APK 侧有：%s" % ", ".join(only_app))
    if only_pc:
        print("[?] 只有 PC 侧有：%s" % ", ".join(only_pc))

    print("\n汇总：一致 %d ｜ 不一致 %d ｜ 调用失败 %d%s"
          % (same, diff, failed, " ｜ 状态指纹：不一致" if not same_state else ""))
    return 1 if (diff or failed or only_app or only_pc or not same_state) else 0


if __name__ == "__main__":
    sys.exit(main())
