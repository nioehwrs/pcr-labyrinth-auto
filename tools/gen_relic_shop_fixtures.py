#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
P4 对拍用例生成器（**第③层：遗物选择 + 商店决策**）—— PC 侧生成，Kotlin 侧单测比对。

基准来源（★全部是 PC 版**真函数**）：
  · 遗物：`pick_card`(5559) / `relic_ctx`(3927) / `relic_pick_note`(4017) / `relic_cards_desc`(4052)
          / `own_relic_stars`(3899) / `count_dot_relics`(3888) / `dot_relic_kinds`(4096)
  · 商店：`shop_plan`(4482) / `shop_item_kind`(4428) / `shop_rate`(4453) / `shop_slot_of`(4549)
  · ⚠`refresh_cases`（刷新判据）：PC 版**没有这个函数** —— 判据是**内联**在 `shop_step`(4641~4714) 里，
    中间还夹着要读设备的 `shop_pool_stats`。本脚本把那段表达式**原样抄**成 `refresh_ref()`
    （每行标了 PC 行号）。⇒ 它只能验"**Kotlin 抄得对不对**"，验不了"这段判据本身是否合理"
    （后者只能靠真机跑一轮商店）。基准文件里 `refresh_cases[].note` 会写明这一点。

★为什么还要造"表里没有的遗物 id"（`extra_rank`）：
  PC 版 2026-10-13 加的几条遗留规则，**用真表的数据走不到**（例如"吸血持有总星数 ≥ 20"：
  真表里吸血遗物太少，总星数最多 3）。为了把那几个分支也纳进对拍，生成器往 `RELIC_RANK` 里
  掺了一批合成条目（id 91xxxx，6 位数 ⇒ 不会混进 `area4_only_ids` 的 5 位数判定），
  并把它们一并写进基准的 `extra_rank`；Kotlin 侧单测用**同一份**合成条目建表 ⇒ 两边同输入。

输入构造（都取自真数据 + 合成条目）：
  · 遗物：候选 2~4 张（掺未知道具 id 走"无数据"路径）；`own` 0~8 件（含**重复 id**，验去重）；
    area = None/1~5；3 区 Boss = 毒液沙罗曼蛇 / 别的 / 没有。
  · 商店：金币取常见档；货架 3~6 件（真遗物 id + 真印记 id + 未知 + 4 层专属），
    价格取真实档（1★1170 / 印记780 / 2★1820 / 3★3800 / 9999），掺 isSold / availability≠1 /
    slots 比槽位短 / page-idx 转不成整数（走 `shop_slot_of` 的兜底）。

用法：
    python tools/gen_relic_shop_fixtures.py --pc "D:\\Users\\zhouyi\\Documents\\LMJ\\labyrinth_auto"
"""
import argparse
import hashlib
import json
import os
import random
import sys
import time

TABLES = ("units.json", "unit_tags.json", "tag_defs.json", "relic_rank.json", "shop_items.json")

# ★合成遗物（只为把真数据到不了的规则分支纳进对拍；两边用同一份）
EXTRA_RANK = {}
for _i in range(7):      # 吸血 ×7：rarity 3 → 持有总星数 21 ≥ REL_STEAL_NEED(20)
    EXTRA_RANK[str(910003 + _i)] = {
        "rarity": 3, "mark": 3, "name": "测试·吸血%d" % (_i + 1), "effect": "自身生命值吸收提升",
        "price": 999, "mark_count": 3, "shop_only": False, "start_skill": 0, "skill_up": 0}
EXTRA_RANK["910015"] = {   # 守备·持续回复 3★（special 分支里排最优先）
    "rarity": 3, "mark": 5, "name": "测试·持续回复", "effect": "持续回复生命值",
    "price": 999, "mark_count": 3, "shop_only": False, "start_skill": 0, "skill_up": 0}
EXTRA_RANK["910016"] = {   # 守备·生命上限 3★（守备小类 1）
    "rarity": 3, "mark": 5, "name": "测试·生命上限", "effect": "生命值＋提升",
    "price": 999, "mark_count": 3, "shop_only": False, "start_skill": 0, "skill_up": 0}
EXTRA_RANK["910025"] = {   # 暴击·暴伤 2★（守备<4 判据里的"暴击≥4"靠它凑）
    "rarity": 2, "mark": 3, "name": "测试·暴伤", "effect": "暴击伤害提升",
    "price": 999, "mark_count": 2, "shop_only": False, "start_skill": 0, "skill_up": 0}
EXTRA_RANK["910026"] = {   # 暴击·吸血 2★（非 3★ 的吸血，走另一支）
    "rarity": 2, "mark": 3, "name": "测试·吸血2", "effect": "自身生命值吸收提升",
    "price": 999, "mark_count": 2, "shop_only": False, "start_skill": 0, "skill_up": 0}
EXTRA_RANK["910035"] = {   # 守备·物理盾 2★
    "rarity": 2, "mark": 5, "name": "测试·物理盾", "effect": "物理攻击无效化屏障",
    "price": 999, "mark_count": 2, "shop_only": False, "start_skill": 0, "skill_up": 0}
EXTRA_RANK["910036"] = {   # 守备·回复强化 1★
    "rarity": 1, "mark": 5, "name": "测试·回复强化", "effect": "回复强化",
    "price": 999, "mark_count": 1, "shop_only": False, "start_skill": 0, "skill_up": 0}
EXTRA_RANK["910042"] = {   # 削弱·毒 1★（dot 判据走 effect 文本）
    "rarity": 1, "mark": 2, "name": "测试·毒", "effect": "使敌人中毒",
    "price": 999, "mark_count": 1, "shop_only": False, "start_skill": 0, "skill_up": 0}


def md5(path):
    try:
        with open(path, "rb") as f:
            return hashlib.md5(f.read()).hexdigest()
    except OSError:
        return None


def refresh_ref(p, area, sh, done, q1):
    """★把 `pcr_auto.shop_step` 第 **4655~4714** 行的表达式原样抄下来（PC 版没有独立函数）。
       4659~4664 取量 ／ 4669~4681 算"刷完还买得起 / 钱宽裕 / q1 把关" ／ 4688~4692 判定"刷" /
       4702~4703 "超过次数但没过门槛" ／ 4705~4712 各种"不刷"的原因。"""
    cfg = p.SHOP_CFG.get(area, p.SHOP_DEFAULT_CFG)
    coin = sh.get("coin") or 0
    cost = sh.get("rerollCost") or 0
    left = sh.get("resettable")
    mx = int(cfg.get("max_refresh") or 0)
    thr = cfg.get("refresh_above") or []
    _rest = [int(i.get("price") or 0) for i in (sh.get("items") or []) if not i.get("isSold")]
    _cheap = min(_rest) if _rest else 0
    _cheap_expect = min(([x for x in _rest if x > 0] + [p.SHOP_CHEAP_EXPECT])
                        if _rest else [p.SHOP_CHEAP_EXPECT])
    _can_after = (coin - cost) >= _cheap_expect
    _rich = (coin - cost) >= 2 * _cheap_expect
    _q1_ok = bool(cfg.get("greedy")) or _rich or (q1 is None) or (q1 >= p.SHOP_REFRESH_MIN_Q1)
    detail = ("刷新评估：q1(刷出≥1个1★)=%s ｜ 费=%s 余额-费=%s 最便宜一件=%s ｜ %s%s"
              % (("%.2f" % q1) if q1 is not None else "?", cost, coin - cost, _cheap,
                 ("钱宽裕(≥两件) → 直接刷" if _rich
                  else ("钱紧 → 需 q1≥%.2f %s" % (p.SHOP_REFRESH_MIN_Q1, "✓" if _q1_ok else "✗"))),
                 "" if _can_after else "  ⚠刷完买不起任何一件"))
    if (done < mx and cost and coin >= cost and (left is None or left > 0)
            and _can_after and _q1_ok):
        need = thr[done] if done < len(thr) else None
        if need is None or coin > need:
            return {"refresh": True, "need": need, "why": "", "detail": detail}
        return {"refresh": False, "need": need,
                "why": "金币 %s 未超过门槛 %s（第%d次）→ 改为退出" % (coin, need, done + 1),
                "detail": detail}
    if cost and coin < cost:
        _why = "刷不起（金币 %s < 费 %s）" % (coin, cost)
    elif not _can_after:
        _why = "刷完买不起任何一件（余额-费 %s < 最便宜 %s）" % (coin - cost, _cheap)
    elif not _q1_ok:
        _why = "钱紧且 q1=%s < %.2f（不值得赌）" % (q1, p.SHOP_REFRESH_MIN_Q1)
    else:
        _why = "已刷 %d/%d 次" % (done, mx)
    return {"refresh": False, "need": None, "why": _why, "detail": detail}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pc", required=True, help="PC 版工程目录（含 pcr_auto.py 与各 json 表）")
    ap.add_argument("--out", default=os.path.join("tests", "fixtures", "relic_shop_fixtures.json"))
    ap.add_argument("--seed", type=int, default=20261014)
    ap.add_argument("--n-relic", type=int, default=220)
    ap.add_argument("--n-shop", type=int, default=160)
    ap.add_argument("--n-refresh", type=int, default=120)
    args = ap.parse_args()

    pc = os.path.abspath(args.pc)
    sys.path.insert(0, pc)
    import pcr_auto as p     # noqa: E402（先放 --pc 进 sys.path）

    rank = p.load_relic_rank()
    rank.update({int(k): v for k, v in EXTRA_RANK.items()})
    p.RELIC_RANK = rank                      # _rk()/area4_only_ids() 读的是这个全局
    items = p.load_shop_items()
    p.SHOP_ITEMS = items
    # ★让基准与本地 config.json **脱钩**：PC 的 `buy_2star_relic()` 会读 config.json（机器不同结果不同）
    #   → 这里统一按"代码默认值"来；实际用的是哪个值**逐条写进用例**（Kotlin 侧显式传参）
    p.app_config = lambda: {}
    rnd = random.Random(args.seed)

    ids = sorted(rank.keys())
    marks = {}
    for i in ids:
        marks.setdefault(rank[i].get("mark"), []).append(i)
    dots = [i for i in ids if p.dot_relic_kinds(i)]
    a4 = sorted(p.area4_only_ids())
    item_ids = sorted(items.keys())
    mark_kind = {}
    for i in item_ids:
        mark_kind.setdefault(items[i]["kind"], []).append(i)

    def own_of(rids):
        """`labOwnRelics` 那个形状（count 故意乱填：PC 认定它是 EffectedCount，不是件数）"""
        return {"guildId": 1001,
                "relics": [{"relicId": r, "count": rnd.choice([1, 1, 2, 19])} for r in rids]}

    # ────────────────────────── ① 遗物选择 ──────────────────────────
    relic_cases = []
    seen = set()

    def relic_case(cards, own, area, boss3):
        p.load_lab_state = (lambda: {"boss": [{"area": 3, "unitId": boss3}]}) if boss3 is not None \
            else (lambda: {})
        ctx = p.relic_ctx(own, {"area": area} if area is not None else {})
        k = p.pick_card(cards, ctx)
        key = (json.dumps(cards, sort_keys=True, ensure_ascii=False),
               json.dumps(own, sort_keys=True, ensure_ascii=False), area, boss3)
        if key in seen:
            return False
        seen.add(key)
        relic_cases.append({
            "input": {"cards": cards, "own": own, "area": area, "boss3": boss3},
            "expect": {
                "stars": p.own_relic_stars(own),
                "own_dot": p.count_dot_relics(own),
                "dots": [p.dot_relic_kinds(c.get("rewardId")) for c in cards],
                "ctx": {"own_dot": ctx["own_dot"], "n_relics": ctx["n_relics"], "star": ctx["star"],
                        "area": ctx["area"], "special": ctx["special"]},
                "pick": k,
                "note": p.relic_pick_note(cards[k], ctx),
                "desc": p.relic_cards_desc(cards, ctx),
            },
        })
        return True

    def draw_cards(k, biased=None):
        pool = list(biased or [])
        while len(pool) < k:
            pool.append(rnd.choice(ids + [888888]))       # 掺未知道具 → "无数据排最后"
        rnd.shuffle(pool)
        return [{"rewardId": r} for r in pool[:k]]

    # 手挑的"局面"：把 relic_sub_rank 的每条分支都点到
    steal_ids = sorted(i for i in ids if rank[i].get("mark") == 3
                       and "生命值吸收" in str(rank[i].get("effect") or ""))
    scenes = [
        ([], None, None),                                   # 空局（dot 判据全 false）
        ([], 3, p.BOSS_VENOM_SALAMANDER),                   # ★特殊条件成立（区=3 + 毒液沙罗曼蛇）
        ([], 2, p.BOSS_VENOM_SALAMANDER),                   # 区≤3 → 仍成立
        ([], 5, p.BOSS_VENOM_SALAMANDER),                   # 区>3 → 不成立
        ([], 3, 306605),                                    # 区=3 但 Boss 是别的 → 不成立
        (dots[:1], None, None),                             # 已有 1 件 dot
        (dots[:2], None, None),                             # 已有 2 件 dot（= REL_DOT_MAX）
        (dots[:3], None, None),                             # ★已有 3 件（> MAX → 削弱内部垫底）
        (steal_ids[:7], None, None),                        # ★吸血总星数 21 ≥ 20
        (steal_ids[:2], None, None),                        # 吸血 < 20
        (steal_ids[:7] + dots[:3], 3, None),                # 吸血≥20 且 dot>MAX
        (["910015"], None, None),                           # 守备·持续回复 3★（非特殊）
        (["910015"], 3, p.BOSS_VENOM_SALAMANDER),           # 特殊 + regen 已持有 → 不提升
        (["910016"], 3, p.BOSS_VENOM_SALAMANDER),           # 特殊 + regen=0 → -1.0
        (["910016", "910025"], None, None),                 # 守备<4 且 暴击≥4 → -0.5
        (["910035", "910036"], None, None),                 # 守备总星数 3 <4 但没触发条件
        (marks.get(2, [])[:2], None, None),                 # 削弱（非 dot 类）
        (marks.get(1, [])[:2], None, None),                 # 加速
        (marks.get(4, [])[:2], None, None),                 # 强化
    ]
    per_scene = max(1, args.n_relic // len(scenes))
    for own_ids, area, boss3 in scenes:
        own = own_of(own_ids + [rnd.choice(own_ids)] if own_ids and rnd.random() < 0.3 else own_ids)
        for _ in range(per_scene):
            biased = own_ids[:1] + [rnd.choice(dots)] if dots else own_ids[:1]
            relic_case(draw_cards(rnd.choice([2, 3, 3, 4]), biased), own, area, boss3)
    # 随机局面（含重复 id / 空 own / 未知 id）
    while len(relic_cases) < args.n_relic:
        own_ids = [rnd.choice(ids + [888888]) for _ in range(rnd.choice([0, 1, 2, 3, 4, 6, 8]))]
        relic_case(draw_cards(rnd.choice([2, 3, 4])), own_of(own_ids),
                   rnd.choice([None, 1, 2, 3, 4, 5]), rnd.choice([None, p.BOSS_VENOM_SALAMANDER, 306605]))

    # ────────────────────────── ② 商店（买什么） ──────────────────────────
    PRICES = {"1★遗物": 1170, "2★遗物": 1820, "3★遗物": 3800, "随机印记": 780,
              "定向印记": 1820, "其他": 500}
    a4_ids = set(str(x) for x in a4)

    def pick_reward(kind):
        if kind == "1★遗物":
            return rnd.choice(marks.get(1, [ids[0]]))
        if kind == "2★遗物":
            return rnd.choice(marks.get(2, [ids[0]]))
        if kind == "3★遗物":
            return rnd.choice(marks.get(3, [ids[0]]))
        if kind == "4层专属":
            k = rnd.choice(a4)
            return int(k) if str(k).isdigit() else k
        if kind == "未知":
            return 888888
        return rnd.choice(mark_kind.get(kind, item_ids))

    shop_cases = []
    seen2 = set()
    shop_kinds = ["1★遗物", "2★遗物", "3★遗物", "随机印记", "定向印记", "4层专属", "未知"]

    def shop_case(area, coin, shelf, slots_n=None, buy2=None):
        sh = {"coin": coin, "items": shelf, "rerollCost": rnd.choice([0, 300, 600]),
              "resettable": rnd.choice([0, 1, 2]),
              "slots": [{"vis": rnd.random() < 0.6} for _ in range(
                  len(shelf) if slots_n is None else slots_n)]}
        key = (area, coin, json.dumps(shelf, sort_keys=True, ensure_ascii=False), buy2, slots_n)
        if key in seen2:
            return False
        seen2.add(key)
        if buy2 is None:
            picked, desc = p.shop_plan(area, sh)
            buy2 = p.buy_2star_relic()
        else:
            p.app_config = (lambda v=buy2: {"shop_buy_2star": v})       # 模拟"配置里关掉 2★"
            picked, desc = p.shop_plan(area, sh)
            p.app_config = lambda: {}                                  # 还原成中性（= 代码默认值）
        refs = [str(it.get("rewardId")) for it in picked]
        kinds, rates, slots = [], [], []
        alive = [it for it in shelf if not it.get("isSold")]
        for it in shelf:
            kd = p.shop_item_kind(it.get("rewardId"))[0]
            kinds.append([str(it.get("rewardId")), kd])
            rates.append([str(it.get("rewardId")), p.shop_rate(it.get("price"), kd)])
            slots.append([str(it.get("rewardId")), p.shop_slot_of(it, alive)])
        shop_cases.append({
            "input": {"area": area, "coin": coin, "sh": sh, "buy_2star": bool(buy2)},
            "expect": {"picked": refs, "desc": desc, "kinds": kinds, "rates": rates, "slots": slots},
        })
        return True

    for area in (None, 3, 4, 4, 5, 5):
        for coin in (0, 160, 780, 1170, 1560, 3100, 4200, 5000, 9000, 12000):
            shelf = []
            for n in range(rnd.choice([3, 4, 6])):
                kind = rnd.choice(shop_kinds)
                rid = pick_reward(kind)
                item = {"page": n // 3, "idx": n % 3, "rewardId": rid,
                        "price": PRICES["其他" if kind in ("4层专属", "未知") else kind]
                        if kind != "4层专属" else 9999,
                        "availability": 1, "isSold": False}
                if rnd.random() < 0.12:
                    item["isSold"] = True                       # 卖了 → 不参与
                elif rnd.random() < 0.15:
                    item["availability"] = 2                    # 货架上没有 → 不可买
                if rnd.random() < 0.08:
                    item.pop("page"); item.pop("idx")            # 缺 page/idx
                if rnd.random() < 0.04:
                    item["page"] = "x"                           # ★转不成整数 → 走 shop_slot_of 兜底
                shelf.append(item)
            shop_case(area, coin, shelf, slots_n=rnd.choice([None, None, 2]))
    # 2★ 开关的另一支
    while len(shop_cases) < args.n_shop:
        shelf = [{"page": i // 3, "idx": i % 3, "rewardId": pick_reward(rnd.choice(shop_kinds)),
                  "price": rnd.choice([780, 1170, 1820, 3800, 9999]), "availability": 1,
                  "isSold": False} for i in range(rnd.choice([3, 6]))]
        shop_case(rnd.choice([None, 3, 4, 5]), rnd.choice([780, 1560, 3100, 9000, 12000]),
                  shelf, buy2=rnd.choice([None, False, True]))

    # ────────────────────────── ③ 商店（刷不刷） ──────────────────────────
    refresh_cases = []
    for c in shop_cases[:args.n_refresh]:
        sh = c["input"]["sh"]
        for done in (0, 1, 2):
            for q1 in (None, 0.1, 0.5, 0.9):
                exp = refresh_ref(p, c["input"]["area"], sh, done, q1)
                refresh_cases.append({
                    "input": {"area": c["input"]["area"], "sh": sh, "done": done, "q1": q1},
                    "expect": exp,
                    "note": "基准抄自 pcr_auto.shop_step 4655~4714（PC 版没有独立函数）",
                })

    out = {
        "meta": {
            "generated_at": time.strftime("%Y-%m-%d %H:%M:%S"),
            "pc_dir": pc,
            "tables": {t: md5(os.path.join(pc, t)) for t in TABLES},
            "counts": {"relic": len(relic_cases), "shop": len(shop_cases), "refresh": len(refresh_cases)},
            "extra_rank_note": "合成遗物条目（真表走不到的分支），两边必须用同一份",
        },
        "extra_rank": EXTRA_RANK,
        "relic_cases": relic_cases,
        "shop_cases": shop_cases,
        "refresh_cases": refresh_cases,
    }
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print("已写出 %s：遗物 %d 条 / 商店 %d 条 / 刷新 %d 条"
          % (args.out, len(relic_cases), len(shop_cases), len(refresh_cases)))


if __name__ == "__main__":
    main()
