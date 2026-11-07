# -*- coding: utf-8 -*-
"""新口径（6:3:1）下：额外金币的边际收益随「当前持币」的变化。

用 shop_curves.json 的 H 曲线（进店后还能再刷一次的状态）作为价值基准：
    H(M) = 手上 M 金 + 一次刷新机会时能期望拿到的分数
边际收益 = H(M + Δ) − H(M)

用法：python tools/gold_marginal.py
输出：tools/out/gold_marginal.md
"""
import io, json, os

HERE = os.path.dirname(os.path.abspath(__file__))
cur = json.load(io.open(os.path.join(HERE, "out", "shop_curves.json"), encoding="utf-8"))


def at(points, money):
    best = 0.0
    for m, s in points:
        if m <= money:
            best = s
        else:
            break
    return best


DELTA = 1000
lines = ["# 额外金币的边际收益（新口径 6:3:1）\n",
         "价值基准：`H(M)` = 持 M 金 + 一次刷新机会时的期望分（`shop_curves.json` 的 h 曲线）\n",
         "边际收益 = `H(M+%d) − H(M)`，单位：分\n" % DELTA]

for tag, name in (("b2", "5 层（3A+2B）"), ("b3", "3 层（3A+3B）")):
    lines.append("\n## %s\n" % name)
    for disc in (65, 75, 100):
        pts = cur[tag]["h"][str(disc)]
        lines.append("\n### 折扣 %d%%\n" % disc)
        lines.append("| 当前金币 | H(M) | H(M+%d) | 边际收益 | 折算 分/千金 |" % DELTA)
        lines.append("|---|---|---|---|---|")
        for m in range(0, 26000, 2000):
            h0, h1 = at(pts, m), at(pts, m + DELTA)
            lines.append("| %d | %.0f | %.0f | **%+.0f** | %+.1f |" % (m, h0, h1, h1 - h0, h1 - h0))

txt = "\n".join(lines)
p = os.path.join(HERE, "out", "gold_marginal.md")
io.open(p, "w", encoding="utf-8", newline="\n").write(txt + "\n")
print(txt[:2600])
print("\n...完整表：", p)
