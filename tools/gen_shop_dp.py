#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成「商店最优购买/刷新策略表」（3 层 / 5 层通用），供 Android 端查表。

模型（2026-11-02 与用户确认）：
  · A = 遗物：1★ 1800 / 2★ 2800 / 3★ 3800（原价），每件 **200 分**
  · B = 随机印记（= 角色）：1200（原价），每件 **100 分**
  · 5 层货架 = 3A + 2B；3 层货架 = 3A + 3B（定向印记不参与，两边都当随机印记建模）
  · 刷新费：第一次 300，第二次 600，**不打折**
  · 折扣四档：100% / 90%（阿尔法强化 -10%）/ 75%（救济院开局 -25%）/ 65%（随机折扣）
    原价 × 折扣 = 实付；门槛换算用 ceil（与游戏取整一致）
  · 阶段：stage0 = 刚进店（还能花 300 刷一次）
          stage1 = 已刷 1 次（还能花 600 刷一次）
          stage2 = 已刷 2 次（不能再刷，纯买）

为什么要出「区间表」而不是一个分界线（用户最初的疑问）：
  换货后的期望 G(y) 是**阶梯函数**，目标 F(M) = max{不换, 各子集先买再换} 是多个阶梯的上包络，
  上包络不是凹的 ⇒ 最优策略会随资金**反复横跳**，只能按区间描述。

输出两份东西（**用途不同**）：

① `shop_curves.json` —— **线上用**（方案 A，2026-11-02 与用户确认，自动同步到 app assets）：
   {
     "b2": { "bCount": 2, "discs": [65..100],
             "g": { "65": [[折后门槛金币, 期望分], ...], ... },   // 换货后不能再换
             "h": { ... } },                                     // 换货后还能再花 600 换一次
     "b3": { ... }
   }
   只打包这两个「与当前货架无关」的量；Android 端 `ShopDp` 对**当前剩余货架**枚举子集（≤64）即得最优
   「买几件 + 买完刷不刷」。好处：「进店时已经买过几件」（闪退重进/买一半失败）天然是合法输入。

② `shop_dp_5f.json` / `shop_dp_3f.json` —— **只作对拍基线**（满货架全预计算的区间表，含每段完整方案）：
   {
     "bCount": 2, "discs": [100, 90, 75, 65],
     "tables": { "0": { "100": { "111": [[0,0,0,0,0,0], [780,0,0,0,1,0], ...] } } }
   }
   行 = [起始金币(含), 买几个1★, 买几个2★, 买几个3★, 买几个印记, 是否花刷新费(1/0)]
   查法：在对应 stage / 折扣 / 星级组合的行里二分找「最后一个 start <= M」。

跑法：`python tools/gen_shop_dp.py`（三份一起生成，曲线顺带拷进 assets）
"""

import json
import os
import shutil
import sys
import time
from bisect import bisect_right

# ────────────────────────── 基础数据 ──────────────────────────

A_PRICE = {1: 1800, 2: 2800, 3: 3800}
B_PRICE = 1200
A_SCORE = 200
B_SCORE = 100

DISCS = [100, 90, 75, 65]          # 百分比：原价 × 折扣（区间表只用这 4 档）
REFRESH_FEE = {0: 300, 1: 600}     # stage0 刷一次 300；stage1 刷一次 600

# G/H 曲线的档位：65~100 步长 5。游戏实际只有 100/90/75/65 四档，
# 多出 4 档纯粹是保险（运行时按整数百分比就近取档，永不越界）。
CURVE_DISCS = list(range(65, 101, 5))

# 10 种 A 星级多重集，及其在 27 种有序组合里出现的次数（用于加权平均）
MULTI = [
    ((1, 1, 1), 1), ((1, 1, 2), 3), ((1, 1, 3), 3), ((1, 2, 2), 3),
    ((1, 2, 3), 6), ((1, 3, 3), 3), ((2, 2, 2), 1), ((2, 2, 3), 3),
    ((2, 3, 3), 3), ((3, 3, 3), 1),
]
MULTI_TOTAL = sum(w for _, w in MULTI)   # = 27

EPS = 1e-9


def price_after(price, disc):
    """原价 → 实付（与游戏一致：向下取整）。"""
    return price * disc // 100


def threshold_after(limit, disc):
    """原价资金门槛 → 折后门槛（向上取整）。"""
    return (disc * limit + 99) // 100


def shelf(stars, disc, b_count):
    """当前货架：[(折后价, 分数), ...]，3 件遗物 + b_count 件印记。"""
    items = [(price_after(A_PRICE[s], disc), A_SCORE) for s in stars]
    items += [(price_after(B_PRICE, disc), B_SCORE)] * b_count
    return items


def subsets(items):
    """所有购买子集：[(掩码, 折后总价, 总分), ...]。"""
    out = []
    n = len(items)
    for mask in range(1 << n):
        cost = 0
        score = 0
        for i in range(n):
            if (mask >> i) & 1:
                cost += items[i][0]
                score += items[i][1]
        out.append((mask, cost, score))
    return out


def counts_of(mask, stars, b_count):
    """掩码 → (买几个1★, 几个2★, 几个3★, 几个印记) —— 同星级遗物完全等价，只记数量。"""
    n1 = n2 = n3 = 0
    for i, s in enumerate(stars):
        if (mask >> i) & 1:
            if s == 1:
                n1 += 1
            elif s == 2:
                n2 += 1
            else:
                n3 += 1
    nb = 0
    for i in range(b_count):
        if (mask >> (len(stars) + i)) & 1:
            nb += 1
    return n1, n2, n3, nb


def stars_key(stars):
    return "".join(str(s) for s in sorted(stars))


# ────────────────────────── 底层期望表 ──────────────────────────

class Model:
    """一套 (bCount) 的 DP 模型：G（换货后买不了再换）与 H（换货后还能再换一次）。"""

    def __init__(self, b_count):
        self.b = b_count
        self.g_points = None       # 原价点
        self.g_values = None       # 对应期望分
        self.g_thr = {}            # disc → 折后门槛列表
        self.h = {}                # disc → {"points", "values", "max_x"}

    # ---- 不能换货时的最优（给定货架、金币） ----
    def w(self, stars, money, disc):
        items = shelf(stars, disc, self.b)
        best = (0, 0, 0)   # score, cost, mask
        for mask, cost, score in subsets(items):
            if cost <= money and (score > best[0] or (score == best[0] and cost < best[1])):
                best = (score, cost, mask)
        return best

    # ---- G：换货后全新随机货架、不能再换 ----
    def build_g(self):
        points = {0}
        for stars, _ in MULTI:
            items = shelf(stars, 100, self.b)        # 原价点（disc = 实付百分比，100 = 不打折）
            points.add(sum(p for p, _ in items))
            for _, cost, _ in subsets(items):
                points.add(cost)
        points = sorted(points)

        values = []
        for x in points:
            total = 0.0
            for stars, w in MULTI:
                total += w * self.w(stars, x, 100)[0]
            values.append(total / MULTI_TOTAL)

        self.g_points = points
        self.g_values = values
        for d in CURVE_DISCS:
            thr = [threshold_after(p, d) for p in points]
            if any(thr[i] >= thr[i + 1] for i in range(len(thr) - 1)):
                raise AssertionError(f"disc={d} 的折后门槛不严格递增，bisect 会错")
            self.g_thr[d] = thr

    def g(self, money, disc):
        if money < 0:
            return 0.0
        arr = self.g_thr[disc]
        i = bisect_right(arr, money) - 1
        return self.g_values[i] if i >= 0 else 0.0

    # ---- V：当前货架已知 + 还能花 600 换一次 ----
    def v(self, stars, money, disc):
        items = shelf(stars, disc, self.b)
        score0, cost0, mask0 = self.w(stars, money, disc)
        best = (score0, False, mask0, cost0, score0)
        for mask, cost, score in subsets(items):
            if cost + 600 <= money:
                total = score + self.g(money - cost - 600, disc)
                if total > best[0] + EPS:
                    best = (total, True, mask, cost, score)
        return best

    # ---- H：随机货架 + 还能花 600 换一次 ----
    def build_h(self):
        for d in CURVE_DISCS:
            points = {0}
            max_all = 0
            for stars, _ in MULTI:
                items = shelf(stars, d, self.b)
                total = sum(p for p, _ in items)
                max_v = total + 600 + self.g_thr[d][-1]
                max_all = max(max_all, max_v)
                points.add(max_v)
                for _, cost, _ in subsets(items):
                    points.add(cost)                          # 不换分支的分段点
                    for t in self.g_thr[d]:                   # 换 600 分支的分段点
                        points.add(cost + 600 + t)
            points = sorted(p for p in points if 0 <= p <= max_all)

            values = []
            for x in points:
                total = 0.0
                for stars, w in MULTI:
                    total += w * self.v(stars, x, d)[0]
                values.append(total / MULTI_TOTAL)

            self.h[d] = {"points": points, "values": values, "max_x": max_all}

    def hv(self, money, disc):
        if money < 0:
            return 0.0
        data = self.h[disc]
        i = bisect_right(data["points"], money) - 1
        return data["values"][i] if i >= 0 else 0.0

    # ---- F：初始货架已知 + 还能花 300 换一次 ----
    def f(self, stars, money, disc):
        items = shelf(stars, disc, self.b)
        score0, cost0, mask0 = self.w(stars, money, disc)
        best = (score0, False, mask0, cost0, score0)
        for mask, cost, score in subsets(items):
            if cost + 300 <= money:
                total = score + self.hv(money - cost - 300, disc)
                if total > best[0] + EPS:
                    best = (total, True, mask, cost, score)
        return best

    # ---- 三张区间表 ----
    def table(self, stage, disc, stars):
        """返回该 (阶段, 折扣, 星级组合) 的分段策略表：[[start, n1,n2,n3,nB, refresh], ...]"""
        items = shelf(stars, disc, self.b)
        total_now = sum(p for p, _ in items)
        fee = REFRESH_FEE.get(stage, 0)

        if stage == 2:
            max_m = total_now
        elif stage == 1:
            max_m = total_now + 600 + self.g_thr[disc][-1]
        else:
            max_m = total_now + 300 + self.h[disc]["max_x"]

        points = {0, max_m}
        for _, cost, _ in subsets(items):
            if 0 <= cost <= max_m:
                points.add(cost)                                  # 不换分支
            if fee > 0:
                if stage == 2:
                    continue
                inner = self.g_thr[disc] if stage == 1 else self.h[disc]["points"]
                for t in inner:                                   # 换货分支
                    m = cost + fee + t
                    if 0 <= m <= max_m:
                        points.add(m)
        points = sorted(points)

        def decide(money):
            if stage == 2:
                score, cost, mask = self.w(stars, money, disc)
                return counts_of(mask, stars, self.b) + (0,)
            if stage == 1:
                _, switched, mask, _, _ = self.v(stars, money, disc)
            else:
                _, switched, mask, _, _ = self.f(stars, money, disc)
            return counts_of(mask, stars, self.b) + (1 if switched else 0,)

        rows = []

        def push(start, plan):
            if rows and rows[-1][1:] == list(plan):
                return                                # 与上一段相同 → 自动延长
            rows.append([start] + list(plan))

        for p in points:
            push(p, decide(p))
        return rows


def build(b_count, out_path):
    t0 = time.time()
    model = Model(b_count)
    model.build_g()
    model.build_h()

    tables = {"0": {}, "1": {}, "2": {}}
    for d in DISCS:
        for stage in (0, 1, 2):
            per_disc = tables[str(stage)].setdefault(str(d), {})
            for stars, _ in MULTI:
                per_disc[stars_key(stars)] = model.table(stage, d, stars)

    payload = {"bCount": b_count, "discs": DISCS, "tables": tables}
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, separators=(",", ":"))

    rows = sum(len(per_s) for st in tables.values() for dd in st.values() for per_s in dd.values())
    size = os.path.getsize(out_path)
    print(f"[ok] {out_path}  bCount={b_count}  表 {10 * 4 * 3} 张 / {rows} 行 / "
          f"{size / 1024:.1f} KB  用时 {time.time() - t0:.1f}s")


def curves_of(b_count):
    """G/H 两条阶梯曲线（运行时查表用）—— 方案 A：只打包这两条，货架部分交给运行时枚举。

    · `g[disc]` = 换货后**不能再换**的期望分：x = 折后门槛金币（递增），v = 期望分
    · `h[disc]` = 换货后**还能再换一次（600）**的期望分
    两套都是「与当前货架无关」的量，所以不会因为「买了一半」而失效。
    """
    model = Model(b_count)
    model.build_g()
    model.build_h()
    out = {"bCount": b_count, "discs": CURVE_DISCS, "g": {}, "h": {}}
    for d in CURVE_DISCS:
        xs = model.g_thr[d]
        out["g"][str(d)] = [[xs[i], round(model.g_values[i], 4)] for i in range(len(xs))]
        hd = model.h[d]
        out["h"][str(d)] = [
            [hd["points"][i], round(hd["values"][i], 4)] for i in range(len(hd["points"]))
        ]
    return out


def build_curves(out_path):
    t0 = time.time()
    payload = {"b2": curves_of(2), "b3": curves_of(3)}
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, separators=(",", ":"))
    pts = sum(len(p["g"][d]) + len(p["h"][d]) for p in payload.values() for d in p["g"])
    size = os.path.getsize(out_path)
    print(f"[ok] {out_path}  b2/b3 × {len(CURVE_DISCS)} 档  共 {pts} 点 / "
          f"{size / 1024:.1f} KB  用时 {time.time() - t0:.1f}s")


def sync_assets(curve_path):
    """把曲线拷进 app assets（线上就读这一份；省得 tools/out 与 assets 各改各的又不一致）。"""
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    assets = os.path.join(root, "android", "app", "src", "main", "assets")
    if not os.path.isdir(assets):
        print(f"[!] 找不到 assets 目录（{assets}）→ 跳过同步，手工拷 {curve_path}")
        return
    dst = os.path.join(assets, "shop_curves.json")
    shutil.copyfile(curve_path, dst)
    print(f"[ok] 已同步 → {dst}")


def main():
    out_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")
    build(2, os.path.join(out_dir, "shop_dp_5f.json"))
    build(3, os.path.join(out_dir, "shop_dp_3f.json"))
    curve_path = os.path.join(out_dir, "shop_curves.json")
    build_curves(curve_path)
    sync_assets(curve_path)


if __name__ == "__main__":
    sys.exit(main())
