# -*- coding: utf-8 -*-
"""流程观测报告 —— 把 PlanProbe 落的 `maze_seq_*.jsonl` 归纳成"节点 → 页面序列"。

用途：给"把迷宫主循环拆成多条链"做决策依据。它回答三个问题：
  ① 每类节点实际经过哪些页、顺序是否稳定（稳定的序列 = 候选链模板）；
  ② 每页停留多久（定链内超时/静置的第一手数据）；
  ③ 哪里在绕圈/回退（= 现有时序 bug 或必须保留的"中断页"）。

数据从哪来（真机/模拟器上跑一局迷宫后）：
    adb pull /sdcard/Android/data/com.bilibili.priconne.yofun.mumu/files/logs/probe ./logs/probe
    （包名换成你实际用的那个；也可以直接在 app 的"日志"页找到 probe 目录）

用法：
    python tools/seq_report.py                          # 读 ./logs/probe
    python tools/seq_report.py logs/probe --top 5
    python tools/seq_report.py logs/probe --by node     # 按"具体节点"分组（看单局内走向）
    python tools/seq_report.py logs/probe --runs        # 先看每局摘要

注意：**只读**，不连游戏、不改任何数据。
"""
import argparse
import glob
import json
import os
import sys
from collections import Counter, defaultdict


def load_rows(paths):
    files = []
    for p in paths:
        if os.path.isdir(p):
            files += sorted(glob.glob(os.path.join(p, "**", "*.jsonl"), recursive=True))
        elif os.path.isfile(p):
            files.append(p)
        else:
            for g in glob.glob(p):
                files.append(g)
    # ★同一文件只读一次（2026-11-02）：`adb pull <设备目录> ./logs/probe` 在目标已存在时会多套一层
    # `logs/probe/probe/`，递归 glob 于是把每个文件读两遍 ⇒ 链数/事件数全部翻倍（实测 66 vs 实际 33）。
    seen = set()
    uniq = []
    for f in files:
        b = os.path.basename(f)
        if b in seen:
            continue
        seen.add(b)
        uniq.append(f)
    rows = []
    for f in uniq:
        try:
            with open(f, encoding="utf-8", errors="ignore") as fh:
                for line in fh:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        d = json.loads(line)
                    except Exception:
                        continue
                    d["_file"] = os.path.basename(f)
                    rows.append(d)
        except OSError:
            continue
    return rows, files


def page_label(r):
    """一页的身份：顶框是主键（同一顶框可能承载不同状态，带上 sttName 更准）。"""
    top = (r.get("top") or "").strip()
    stt = (r.get("sttName") or "").strip()
    if not top:
        return "(无顶框:%s)" % ((r.get("view") or "?").strip() or "?")
    # 只有"同一顶框承载多种内容"的已知情形才附状态名，避免序列名过长
    if top == "DialogLabyrinthSelection" and stt:
        return "%s/%s" % (top, stt)
    return top


def segments(pages):
    """按 (run, block) 切段：一段 = 一个节点的连续访问。"""
    out = []
    cur_key, cur = None, []
    for r in pages:
        key = (r.get("run") or "", r.get("block") or r.get("node") or "")
        if key != cur_key:
            if cur:
                out.append((cur_key, cur))
            cur_key, cur = key, []
        cur.append(r)
    if cur:
        out.append((cur_key, cur))
    return out


def fmt_seq(seq):
    return " → ".join(seq)


def pct(a, b):
    return 0.0 if not b else a * 100.0 / b


def main():
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("paths", nargs="*", default=["logs/probe"], help="jsonl 文件或目录（默认 logs/probe）")
    ap.add_argument("--by", choices=["ptype", "node"], default="ptype",
                    help="按节点类型聚合（默认，跨局可比）或按具体节点")
    ap.add_argument("--top", type=int, default=3, help="每组显示前 N 条序列（默认 3）")
    ap.add_argument("--min-visits", type=int, default=1, help="少于 N 次访问的组不显示（默认 1）")
    ap.add_argument("--runs", action="store_true", help="只打每局摘要")
    args = ap.parse_args()

    paths = args.paths or ["logs/probe"]
    rows, files = load_rows(paths)
    if not rows:
        print("没读到数据：%s" % ("、".join(paths)))
        print("先跑一局迷宫，再把 app 的 logs/probe 目录 pull 下来。")
        return 1

    pages = [r for r in rows if r.get("ev") == "page"]
    stalls = [r for r in rows if r.get("ev") == "stall"]
    ends = [r for r in rows if r.get("ev") == "run_end"]

    by_run = defaultdict(list)
    for r in pages:
        by_run[r.get("run") or "?"].append(r)
    for k in by_run:
        by_run[k].sort(key=lambda x: x.get("i") or 0)

    print("=" * 78)
    print("文件 %d 个 ｜ 局 %d ｜ 页面记录 %d ｜ stall(>30s) %d"
          % (len(files), len(by_run), len(pages), len(stalls)))
    print("=" * 78)

    if args.runs:
        for run in sorted(by_run):
            ps = by_run[run]
            end = next((e for e in ends if e.get("run") == run), {})
            tip = "%s（%s，%d 页）" % (run, end.get("reason") or "无收尾行", len(ps))
            nodes = []
            for _k, seg in segments(ps):
                t = seg[0].get("block") or seg[0].get("node") or "?"
                if not nodes or nodes[-1] != t:
                    nodes.append(t)
            print("\n局 %s" % tip)
            print("  节点顺序 %s" % (" → ".join(nodes) if nodes else "—"))
        return 0

    # ── ① 节点 → 页面序列 ──
    groups = defaultdict(list)          # groupKey -> [seq segments]
    for run in sorted(by_run):
        for (rk, seg) in segments(by_run[run]):
            r0 = seg[0]
            key = (r0.get("ptype") or "?").strip() if args.by == "ptype" \
                else "%s#%s" % ((r0.get("ptype") or "?"), (r0.get("block") or "?"))
            groups[key].append([page_label(r) for r in seg])

    print("\n【%s → 页面序列】" % ("节点类型" if args.by == "ptype" else "具体节点"))
    for key in sorted(groups, key=lambda k: -len(groups[k])):
        segs = groups[key]
        if len(segs) < args.min_visits:
            continue
        cnt = Counter(tuple(s) for s in segs)
        print("\n%s（访问 %d 次，%d 种序列）" % (key, len(segs), len(cnt)))
        for seq, c in cnt.most_common(args.top):
            print("  %5.1f%% ×%-3d %s" % (pct(c, len(segs)), c, fmt_seq(seq)))

    # ── ② 页面停留 ──
    dwell = defaultdict(list)
    for r in pages:
        ms = r.get("ms")
        if isinstance(ms, (int, float)):
            dwell[page_label(r)].append(ms)
    print("\n【页面停留（ms，n / 中位 / P90 / 最大）】")
    for lab, arr in sorted(dwell.items(), key=lambda kv: -len(kv[1])):
        a = sorted(arr)
        med = a[len(a) // 2]
        p90 = a[min(len(a) - 1, int(len(a) * 0.9))]
        print("  %-46s %4d  %7d %8d %9d" % (lab[:46], len(a), med, p90, a[-1]))

    # ── ③ 绕圈 / 重复页 / 长时间停留 ──
    # 通用弹层（DialogCommon*：特别装备三步、通用确认框…）天然就是 A→B→A，不算绕圈
    INTERRUPT_PREFIX = ("DialogCommon", "DialogFlatout")
    print("\n【绕圈（同一节点内 A→B→A，已排除通用弹层）】")
    loops = 0
    for run in sorted(by_run):
        for (rk, seg) in segments(by_run[run]):
            labs = [page_label(r) for r in seg]
            for i in range(len(labs) - 2):
                if labs[i] == labs[i + 2] != labs[i + 1]:
                    tri = labs[i:i + 3]
                    if any(x.startswith(INTERRUPT_PREFIX) for x in tri):
                        continue
                    print("  局 %s 节点 %s：%s" % (run, rk[1], " → ".join(labs[max(0, i - 1):i + 3])))
                    loops += 1
                    break
    if not loops:
        print("  （无）")

    print("\n【同一节点内反复回到同一页（≥3 次）—— 回流点，链化时要显式建模】")
    rep = 0
    for run in sorted(by_run):
        for (rk, seg) in segments(by_run[run]):
            c = Counter(page_label(r) for r in seg)
            for lab, k in c.items():
                if k >= 3:
                    print("  局 %s 节点 %s：%s 出现 %d 次" % (run, rk[1], lab, k))
                    rep += 1
    if not rep:
        print("  （无）")

    if stalls:
        print("\n【停留 >30s 的页（候选：卡点 / 长演出 / 该建超时）】")
        for r in stalls[:20]:
            print("  局 %s 节点 %s [%s] %s 已停 %ss（%s 轮）prev=%s"
                  % (r.get("run"), r.get("node"), r.get("ptype"), r.get("top"),
                     round((r.get("ms") or 0) / 1000.0), r.get("rounds"), r.get("prev") or "—"))

    # ── ④ Boss 链影子（BossChain 写的 ev=chain）──
    chains = [r for r in rows if r.get("ev") == "chain"]
    print("\n【Boss 链影子（按局看步进轨迹）】")
    if not chains:
        print("  （没有 chain 事件：要么没跑到 Boss 节点，要么影子链被禁用）")
    else:
        # 先给一条"够不够格让链接管动作"的结论，再逐局看轨迹（2026-11-02）
        starts = [r for r in chains if r.get("verdict") == "start"]
        ends = [r for r in chains if r.get("verdict") == "end"]
        done = [r for r in ends if "链完成" in (r.get("note") or "")]
        ua = Counter(r.get("verdict") for r in chains)
        adv = ua["advance"] + ua["back"]          # 计入"步进"的总次数
        drift = ua["unknown"] + ua["back"]        # 认不出 + 回退 = 链解释不了的次数
        rate = (1.0 - drift / adv) if adv else 0.0
        pgs = [r.get("pages") for r in ends if isinstance(r.get("pages"), int)]
        print("  ── 汇总：%d 条链（走完 %d / 提前结束 %d），步进 %d 次"
              % (len(starts), len(done), len(ends) - len(done), adv))
        print("     异常：unknown %d / back %d / timeout %d / guard %d；平均每链 %s 次页切换（含战斗视图抖动）"
              % (ua["unknown"], ua["back"], ua["timeout"], ua["guard"],
                 round(sum(pgs) / len(pgs), 1) if pgs else "—"))
        print("     吻合度 %.0f%%（异常 %d / 步进 %d）→ %s"
              % (rate * 100, drift, adv,
                 "够格驱动动作" if (rate >= 0.95 and len(starts) >= 5)
                 else ("吻合但样本不足（<5 条链），再多跑几局" if rate >= 0.95
                       else "先补链（有认不出/回退的页）再谈驱动")))
        byrc = defaultdict(list)
        for r in chains:
            byrc[r.get("run") or "?"].append(r)
        for run in sorted(byrc):
            rs = byrc[run]
            uc = Counter(r.get("verdict") for r in rs)
            print("  局 %s：%d 事件（advance %d / back %d / unknown %d / timeout %d / guard %d）"
                  % (run, len(rs), uc["advance"], uc["back"], uc["unknown"], uc["timeout"],
                     uc["guard"]))
            for r in rs[:25]:
                print("     step%-2s %-10s %s" % (r.get("step"), r.get("stepName"), r.get("note")))
            if len(rs) > 25:
                print("     ...（还有 %d 条）" % (len(rs) - 25))

    print("\n提示：序列越集中（top1 占比高）越适合做链；占比低的多为「中断页插进来」或时序不稳。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
