# -*- coding: utf-8 -*-
"""对比「各 1/3」与「6:3:1」两套商店曲线，输出报告。

用法：python tools/compare_shop_p631.py
输出：tools/out_p631/compare_report.md
"""
import io, json, os

HERE = os.path.dirname(os.path.abspath(__file__))
OLD = os.path.join(HERE, "out", "shop_curves.json")
NEW = os.path.join(HERE, "out_p631", "shop_curves.json")
OUT = os.path.join(HERE, "out_p631", "compare_report.md")

old = json.load(io.open(OLD, encoding="utf-8"))
new = json.load(io.open(NEW, encoding="utf-8"))


def curve(blk, stage, disc):
    """返回 [(money, score), ...]"""
    return blk[stage][str(disc)]


def at(points, money):
    """最后一个 money<=M 的分数（阶梯查表）"""
    best = 0.0
    for m, s in points:
        if m <= money:
            best = s
        else:
            break
    return best


lines = []
lines.append("# 商店 DP：各 1/3 vs 6:3:1 对比\n")
lines.append("模型：3 件遗物各自按权重抽星级；其它（价格/分数/刷新费/印记数）完全一致。\n")
lines.append("`g` = 刷新后不能再刷（终止层）的期望分；`h` = 刷新后还能再刷一次。\n")

for tag, name in (("b2", "5 层（3A+2B）"), ("b3", "3 层（3A+3B）")):
    lines.append("\n## %s\n" % name)
    lines.append("| 折扣 | 金币 | 旧G(1/3) | 新G(6:3:1) | ΔG | 旧H | 新H | ΔH | 刷新收益 旧→新 |")
    lines.append("|---|---|---|---|---|---|---|---|---|")
    for disc in (65, 75, 90, 100):
        for money in (2000, 5000, 8000, 12000, 16000, 22000):
            og = at(curve(old[tag], "g", disc), money)
            ng = at(curve(new[tag], "g", disc), money)
            oh = at(curve(old[tag], "h", disc), money)
            nh = at(curve(new[tag], "h", disc), money)
            lines.append(
                "| %d%% | %d | %.0f | %.0f | %+.0f | %.0f | %.0f | %+.0f | %+.0f → %+.0f |"
                % (disc, money, og, ng, ng - og, oh, nh, nh - oh, oh - og, nh - ng)
            )

# 汇总
def summary(tag):
    tot_o = tot_n = 0.0
    n = 0
    worst = (0.0, None)
    for disc in old[tag]["discs"]:
        for stage in ("g", "h"):
            co, cn = curve(old[tag], stage, disc), curve(new[tag], stage, disc)
            for (m, so), (_, sn) in zip(co, cn):
                tot_o += so
                tot_n += sn
                n += 1
                if abs(sn - so) > abs(worst[0]):
                    worst = (sn - so, (disc, stage, m))
    lines.append(
        "\n- `%s` 全曲线平均：旧 %.1f → 新 %.1f（**%+.1f**）；最大单点差 %+.1f @disc=%s/%s/M=%s"
        % (tag, tot_o / n, tot_n / n, (tot_n - tot_o) / n, worst[0], worst[1][0], worst[1][1], worst[1][2])
    )

for tag in ("b2", "b3"):
    summary(tag)

io.open(OUT, "w", encoding="utf-8", newline="\n").write("\n".join(lines) + "\n")
print("已写", OUT)
print("\n".join(lines[:14]))
