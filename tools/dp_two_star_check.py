"""
扩展 ShopMdp.solve：track b2（买 2★ 概率），在 disc=65..100(步5) 下算
  R=0     下界 T0   = 第一个 b2>=0.5 的 G（买得起且值得买 -> 越大越能买）
  R=1/R=2 上界 U1/U2 = 最后一个 b2>=0.5 的 G（再往上就去刷新了 -> 区间上界）
并拉 refreshGateG（refr>=0.9 否则 >=0.5）对照。
对照手填表 twoStarThresholds(R1=4100,R2=3800)。
"""
import random

RELIC_BASE = {1: 1800, 2: 2800, 3: 3800}
MARK_BASE = 1200
VAL_RELIC = 200
VAL_MARK = 100
REFRESH_COST = [300, 600]


def sample_shelf(rng, disc, allow3, buy2):
    mark_price = round(MARK_BASE * disc)
    shelf = []
    for _ in range(3):
        r = 1 + rng.randint(0, 2)
        if r == 3 and not allow3:
            continue
        if r == 2 and not buy2:
            continue
        shelf.append((r, round(RELIC_BASE[r] * disc), VAL_RELIC))
    for _ in range(2):
        shelf.append((0, mark_price, VAL_MARK))
    return shelf


def lift(w, gold, step):
    if gold <= 0:
        return w[0]
    gi = max(0, min(len(w) - 1, int(round(gold / step))))
    return w[gi]


def eval_shelves(shelves, grid, w_next, r_level, max_refresh, mark_price):
    step = grid[1] - grid[0]
    c = 0 if w_next is None else REFRESH_COST[min(max_refresh - r_level, len(REFRESH_COST) - 1)]
    best = [0.0] * len(grid)
    bb3 = [0.0] * len(grid)
    bb2 = [0.0] * len(grid)
    bref = [0.0] * len(grid)
    # 预计算每个 mask 的 (cost,value,b2f,b3f)
    for shelf in shelves:
        n = len(shelf)
        masks = []
        for mask in range(1 << n):
            cost = value = 0
            b2f = b3f = False
            for i in range(n):
                if mask & (1 << i):
                    r, p, v = shelf[i]
                    cost += p
                    value += v
                    if r == 2:
                        b2f = True
                    if r == 3:
                        b3f = True
            masks.append((cost, value, b2f, b3f))
        sb = [0.0] * len(grid)
        sb3 = [0.0] * len(grid)
        sb2 = [0.0] * len(grid)
        sref = [0.0] * len(grid)
        for gi, g in enumerate(grid):
            bv = -1.0
            b3 = b2 = ref = 0.0
            for cost, value, b2f, b3f in masks:
                if cost > g:
                    continue
                g_after = g - cost
                did_refr = (w_next is not None and r_level > 0 and g_after >= c
                            and (g_after - c) >= mark_price)
                vv = value + lift(w_next, g_after - c, step) if did_refr else float(value)
                if vv > bv:
                    bv = vv
                    b3 = 1.0 if b3f else 0.0
                    b2 = 1.0 if b2f else 0.0
                    ref = 1.0 if did_refr else 0.0
            sb[gi] = bv
            sb3[gi] = b3
            sb2[gi] = b2
            sref[gi] = ref
        for gi in range(len(grid)):
            best[gi] += sb[gi]
            bb3[gi] += sb3[gi]
            bb2[gi] += sb2[gi]
            bref[gi] += sref[gi]
    m = len(shelves)
    return ([x / m for x in best], [x / m for x in bb3],
            [x / m for x in bb2], [x / m for x in bref])


def solve(disc, max_refresh=2, g_max=15000, step=100, n_samples=12000, seed=1):
    rng = random.Random(seed)
    mark_price = round(MARK_BASE * disc)
    grid = list(range(0, g_max + 1, step))
    shelves = [sample_shelf(rng, disc, True, True) for _ in range(n_samples)]
    w0, b3_0, b2_0, _ = eval_shelves(shelves, grid, None, 0, max_refresh, mark_price)
    w_by = [w0]
    b2_by = [b2_0]
    b3_by = [b3_0]
    refr_by = [None]
    w_prev = w0
    for r in range(1, max_refresh + 1):
        w, b3, b2, refr = eval_shelves(shelves, grid, w_prev, r, max_refresh, mark_price)
        w_by.append(w)
        b2_by.append(b2)
        b3_by.append(b3)
        refr_by.append(refr)
        w_prev = w

    def first_ge5(arr):
        for gi, x in enumerate(arr):
            if x >= 0.5:
                return grid[gi]
        return None

    def last_ge5(arr):
        last = None
        for gi, x in enumerate(arr):
            if x >= 0.5:
                last = grid[gi]
        return last

    def gate_of(arr):
        g = None
        for gi, x in enumerate(arr):
            if x >= 0.9:
                g = grid[gi]
                break
        if g is None:
            for gi, x in enumerate(arr):
                if x >= 0.5:
                    g = grid[gi]
                    break
        return g

    gate2 = gate_of(refr_by[2])   # R=2 顶（刚进店）刷新门槛
    gate1 = gate_of(refr_by[1])   # R=1（已刷1次）刷新门槛
    price2 = round(RELIC_BASE[2] * disc)
    # 真实 DP 行为：R>0 时「不刷新才买 2★」-> 可买余金区间 = [price2, gateR]
    # 若 gateR < price2 -> 永远刷新、不买 2★
    band1 = f"[{price2},{gate1}]" if (gate1 is not None and gate1 >= price2) else "永不买"
    band2 = f"[{price2},{gate2}]" if (gate2 is not None and gate2 >= price2) else "永不买(门槛<价)"
    t0 = first_ge5(b2_by[0])
    return disc, t0, price2, gate1, gate2, band1, band2


def analyze_restcoin(disc, n_samples=12000, g_max=15000, step=100):
    """按 restCoin（已买 1★/印记后的余金）重算 2★ 最优购买概率。"""
    rng = random.Random(1)
    mark_price = round(MARK_BASE * disc)
    grid = list(range(0, g_max + 1, step))
    p1 = round(RELIC_BASE[1] * disc)
    shelves = [sample_shelf(rng, disc, True, True) for _ in range(n_samples)]
    w0, b3_0, b2_0, _ = eval_shelves(shelves, grid, None, 0, 2, mark_price)
    w1, b3_1, b2_1, _ = eval_shelves(shelves, grid, w0, 1, 2, mark_price)
    w2, b3_2, b2_2, _ = eval_shelves(shelves, grid, w1, 2, 2, mark_price)
    b2_by = [b2_0, b2_1, b2_2]

    def b2_at(r, gtotal):
        gi = max(0, min(len(grid) - 1, int(round(gtotal / step))))
        return b2_by[r][gi]

    res = {}
    for r in (1, 2):
        acc = [0.0] * len(grid)
        cnt = [0] * len(grid)
        for shelf in shelves:
            has2 = any(star == 2 for star, _, _ in shelf)
            if not has2:
                continue
            base = sum(pr for star, pr, _ in shelf if star == 1) + 2 * mark_price
            for gi, rc in enumerate(grid):
                gtotal = rc + base
                if gtotal > g_max:
                    continue
                acc[gi] += b2_at(r, gtotal)
                cnt[gi] += 1
        res[r] = [ (acc[i] / cnt[i]) if cnt[i] else 0.0 for i in range(len(grid)) ]
    return grid, res, mark_price, p1


def print_restcoin_intervals():
    print("2★购买区间（restCoin = 已买1★/印记后余金；★=该余金下'买2★'为最优的概率）")
    print("买区间=概率>=0.5；跳过的区间=概率<0.5（直接刷新）\n")
    for d in (65, 75, 85, 100):
        grid, res, mp, p1 = analyze_restcoin(d / 100.0)
        print(f"==== disc={d}  2★价={round(RELIC_BASE[2]*d/100)}  印记价={mp}  1★价={p1} ====")
        for r in (2, 1):
            arr = res[r]
            buys = [grid[i] for i in range(len(grid)) if arr[i] >= 0.5 and grid[i] >= round(RELIC_BASE[2]*d/100)]
            # 合并连续区间
            intervals = []
            if buys:
                s = buys[0]; prev = buys[0]
                for x in buys[1:]:
                    if x - prev <= 100:
                        prev = x
                    else:
                        intervals.append((s, prev)); s = x; prev = x
                intervals.append((s, prev))
            label = "刚进店R=2" if r == 2 else "已刷1次R=1"
            print(f"  [{label}] 买2★余金区间: {intervals if intervals else '空(从不买)'}")
        print()


def exact_2star_decision(disc, n_samples=15000, g_max=15000, step=100):
    rng = random.Random(1)
    mp = round(MARK_BASE * disc)
    grid = list(range(0, g_max + 1, step))
    shelves = [sample_shelf(rng, disc, True, True) for _ in range(n_samples)]
    w0, _, _, _ = eval_shelves(shelves, grid, None, 0, 2, mp)
    w1, _, _, _ = eval_shelves(shelves, grid, w0, 1, 2, mp)
    w2, _, _, _ = eval_shelves(shelves, grid, w1, 2, 2, mp)
    W = [w0, w1, w2]  # W[r] = value at level r (r refreshes remaining)

    def vat(arr, g):
        gi = max(0, min(len(grid) - 1, int(round(g / step))))
        return arr[gi]

    p2 = round(RELIC_BASE[2] * disc)
    print(f"disc={disc}  2★价={p2}  印记价={mp}")
    for R in (2, 1):
        c = REFRESH_COST[0] if R == 2 else REFRESH_COST[1]
        print(f"  --- R={R}（剩余刷新{R}次，本步刷新费{c}）余金-> 2★决策 ---")
        line = ""
        for r in range(0, 12001, 200):
            opts_no = [vat(W[R - 1], r)]
            if r >= c and (r - c) >= mp:
                opts_no.append(vat(W[R - 1], r - c))
            best_no = max(opts_no)
            best_w = -1.0
            if r >= p2:
                g = r - p2
                o = [vat(W[R - 1], g)]
                if g >= c and (g - c) >= mp:
                    o.append(vat(W[R - 1], g - c))
                best_w = max(o) + 200.0
            buy = (best_w > best_no) if r >= p2 else False
            line += ("B" if buy else ".")
        print("  余金 0..12000(步200): " + line)
        print("  (B=买2★  .=跳过直接刷新; 每字符200金)")


def threshold_table():
    discs = [0.65, 0.70, 0.75, 0.80, 0.85, 0.90, 0.95, 1.0]
    step = 200

    def stable_buy_start(dec):
        n = len(dec)
        for start in range(n):
            ok = True
            for w in range(start, n, 5):
                if sum(dec[w:w + 5]) < 4:
                    ok = False
                    break
            if ok:
                return start * step
        return None

    print("折扣  | 2★价 | R=2买线(剩多少金才许买) | R=1买线")
    print("------|------|--------------------------|--------")
    for disc in discs:
        rng = random.Random(1)
        mp = round(MARK_BASE * disc)
        grid = list(range(0, 14001, step))
        shelves = [sample_shelf(rng, disc, True, True) for _ in range(20000)]
        w0, _, _, _ = eval_shelves(shelves, grid, None, 0, 2, mp)
        w1, _, _, _ = eval_shelves(shelves, grid, w0, 1, 2, mp)
        w2, _, _, _ = eval_shelves(shelves, grid, w1, 2, 2, mp)
        W = [w0, w1, w2]
        p2 = round(RELIC_BASE[2] * disc)

        def vat(arr, g):
            gi = max(0, min(len(grid) - 1, int(round(g / step))))
            return arr[gi]

        rows = {}
        for R in (2, 1):
            c = REFRESH_COST[0] if R == 2 else REFRESH_COST[1]
            dec = []
            for r in range(0, 12001, step):
                opts_no = [vat(W[R - 1], r)]
                if r >= c and (r - c) >= mp:
                    opts_no.append(vat(W[R - 1], r - c))
                best_no = max(opts_no)
                best_w = -1.0
                if r >= p2:
                    g = r - p2
                    o = [vat(W[R - 1], g)]
                    if g >= c and (g - c) >= mp:
                        o.append(vat(W[R - 1], g - c))
                    best_w = max(o) + 200.0
                dec.append((best_w > best_no) if r >= p2 else False)
            rows[R] = stable_buy_start(dec)
        r2 = rows[2]
        r1 = rows[1]
        r2s = f"{r2}" if r2 is not None else "≈12000+(几乎不买)"
        r1s = f"{r1}" if r1 is not None else "≈12000+(几乎不买)"
        print(f"{int(disc*100):>4}  | {p2:>4} | {r2s:>24} | {r1s}")


if __name__ == "__main__":
    import sys
    if len(sys.argv) > 1 and sys.argv[1] == "curve":
        disc = 0.65
        rng = random.Random(1)
        mark_price = round(MARK_BASE * disc)
        grid = list(range(0, 15001, 100))
        shelves = [sample_shelf(rng, disc, True, True) for _ in range(20000)]
        w0, b3_0, b2_0, _ = eval_shelves(shelves, grid, None, 0, 2, mark_price)
        w1, b3_1, b2_1, refr1 = eval_shelves(shelves, grid, w0, 1, 2, mark_price)
        w2, b3_2, b2_2, refr2 = eval_shelves(shelves, grid, w1, 2, 2, mark_price)
        print(f"disc=65  2★价={round(RELIC_BASE[2]*disc)}  markPrice={mark_price}")
        print("   G    b2@R2 b2@R1 b2@R0  refr@R2 refr@R1")
        for g in [1500, 2000, 2500, 3000, 3500, 4000, 4500, 5000, 6000, 7000, 8000, 9000, 10000, 12000, 15000]:
            gi = g // 100
            print(f"{g:6d}  {b2_2[gi]:.2f}  {b2_1[gi]:.2f}  {b2_0[gi]:.2f}   {refr2[gi]:.2f}    {refr1[gi]:.2f}")
    elif len(sys.argv) > 1 and sys.argv[1] == "restcoin":
        print_restcoin_intervals()
    elif len(sys.argv) > 1 and sys.argv[1] == "exact":
        for d in (65, 100):
            exact_2star_decision(d / 100.0)
            print()
    elif len(sys.argv) > 1 and sys.argv[1] == "table":
        threshold_table()
    else:
        print("disc%  R0_T(下界)  2★价  gateR1  gateR2  R1可买区间      R2可买区间       手填表(R1=4100,R2=3800)")
        for d in range(65, 101, 5):
            disc, t0, p2, g1, g2, b1, b2 = solve(d / 100.0)
            print(f"  {d:3d}    {str(t0):>6}    {p2:>4}   {str(g1):>5}   {str(g2):>5}   {b1:>12}   {b2:>14}")
