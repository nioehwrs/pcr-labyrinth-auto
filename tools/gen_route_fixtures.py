#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
P5 对拍用例生成器（**节点价值 / 最优路线 / 节点选择**）—— PC 侧生成，Kotlin 侧单测比对。

基准来源（★全是 PC 版**真函数**）：
  · `block_score`(5617) / `build_dp`(5623) / `path_from`(5642) / `optimal_path`(5652)
   / `area_theoretical`(5665) / `map_by_area`(5673) / `check_value_perfect`(5681)
   / `pick_node`(5433) / `fmt_route`(5757) / `refresh_route`(5860)

数据源：
  · **真图**：`labyrinth_state.json` 的 `map`（刷开局验过的那张，78 节点，五区）——
    文件里的 `value` 就是 PC 当年在 event_value=350 口径下算出的 **实际 18150 / 理论 18150 / 逐区明细**，
    生成器会顺手核对一遍（对不上会告警），Kotlin 侧要复现的正是这个。
  · **合成图**：随机 DAG（每区 2~5 列 × 1~3 行，含"跨区 next""不可达节点""同列平局"等边角）。

三个口径（PC 当年也是这么验的，见 pcr_auto.py 5590~5602）：
  `event_value` = 350（偏事件，默认）／100（偏遗物）／0（忽略事件）。

用法：
    python tools/gen_route_fixtures.py --pc "D:\\Users\\zhouyi\\Documents\\LMJ\\labyrinth_auto"
"""
import argparse
import json
import os
import random
import sys
import time


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pc", required=True, help="PC 版工程目录（含 pcr_auto.py 与 labyrinth_state.json）")
    ap.add_argument("--out", default=os.path.join("tests", "fixtures", "route_fixtures.json"))
    ap.add_argument("--seed", type=int, default=20261015)
    ap.add_argument("--maps", type=int, default=30, help="合成地图数量")
    ap.add_argument("--picks", type=int, default=140, help="pick_node 用例数量")
    args = ap.parse_args()

    pc = os.path.abspath(args.pc)
    sys.path.insert(0, pc)
    import pcr_auto as p     # noqa: E402

    rnd = random.Random(args.seed)

    # ── 数据源：真图 + 合成图 ──────────────────────────────────────────────
    maps = []          # (名字, panels, saved_route|None, 文件的 event_value|None, 文件里的 value|None)
    try:
        d = json.load(open(os.path.join(pc, "labyrinth_state.json"), encoding="utf-8"))
        if d.get("map"):
            maps.append(("真图 enter_id=%s（%d 节点）" % (d.get("enter_id"), len(d["map"])),
                         d["map"], d.get("route") or {}, d.get("event_value"), d.get("value")))
            print("已载入真图：%d 节点；文件里 event_value=%s，value=%s"
                  % (len(d["map"]), d.get("event_value"), json.dumps(d.get("value") or {}, ensure_ascii=False)[:120]))
    except Exception as e:
        print("[!] 读不到 labyrinth_state.json：%s（只用合成图）" % e)

    def synth_map():
        panels = []
        for area in range(1, 6):
            ncol = rnd.randint(2, 5)
            per = rnd.randint(1, 3)
            cols = {}
            for c in range(1, ncol + 1):
                for r in range(1, per + 1):
                    bid = area * 10000 + c * 100 + r
                    cols.setdefault(c, []).append(bid)
                    panels.append({"area": area, "column": c, "row": r, "blockId": bid,
                                   "blockType": rnd.choice([1, 2, 2, 3, 4, 5, 6, 7, 8]),
                                   "questId": None, "next": [], "visited": 0})
            for c in range(1, ncol):
                for bid in cols[c]:
                    nxt = rnd.sample(cols[c + 1], rnd.randint(1, min(2, len(cols[c + 1]))))
                    if rnd.random() < 0.08:
                        nxt = nxt + [rnd.choice([99999, (area + 9) * 10000 + 101])]   # 跨区/不存在的后继
                    next(x for x in panels if x["blockId"] == bid)["next"] = nxt
        return panels

    for i in range(args.maps):
        maps.append(("合成图#%d" % i, synth_map(), None, None, None))

    # ── ① block_score 探针 ────────────────────────────────────────────────
    score_cases = []
    for ev in (350, 100, 0):
        p.BLOCK_SCORE[5] = ev
        for bt in list(range(0, 10)) + [None]:
            for ig in (False, True):
                score_cases.append({
                    "input": {"block_type": bt, "ignore_event": ig, "event_value": ev},
                    "expect": p.block_score(bt, ig),
                })
    p.BLOCK_SCORE[5] = 350

    # ── ② 地图：DP / 最优路线 / 理论分 / 判定 / 渲染 / refresh_route ──────
    map_cases = []
    for name, panels, saved_route, file_ev, file_value in maps:
        for evv in (350, 100, 0):
            p.BLOCK_SCORE[5] = evv
            by_area = {}
            for area, blk in sorted(p.map_by_area(panels).items()):
                dp, nxt = p.build_dp(blk, False)
                sc, path = p.optimal_path(blk, False)
                by_area[str(area)] = {
                    "dp": {str(k): v for k, v in dp.items()},
                    "nxt": {str(k): v for k, v in nxt.items()},
                    "optimal": {"score": sc, "path": path},
                    "theoretical": p.area_theoretical(blk, False),
                    "fmt": p.fmt_route(blk, path),
                }
            ok, act, theo, det = p.check_value_perfect(panels, False)
            check = {"ok": ok, "actual": act, "theory": theo,
                     "details": [{"area": a, "actual": s, "theory": t, "path": path} for a, s, t, path in det]}

            # refresh_route 两条路：① 现算 DP（把 load_lab_state 关掉）；② 用"刷开局记住的路线"
            p.load_lab_state = lambda: None
            r_now, r_saved = [], []
            for area, blk in sorted(p.map_by_area(panels).items()):
                for tag, cur in (("first", blk[0]["blockId"]),
                                 ("mid", blk[min(2, len(blk) - 1)]["blockId"]),
                                 ("last", blk[-1]["blockId"])):
                    r_now.append({"area": area, "cur": cur, "tag": tag,
                                  "path": p.refresh_route(None, area, cur, force=True, panels=panels)})
            if saved_route:
                p.load_lab_state = (lambda sr=saved_route: {"route": sr})
                for area, blk in sorted(p.map_by_area(panels).items()):
                    for tag, cur in (("first", blk[0]["blockId"]),
                                     ("mid", blk[min(2, len(blk) - 1)]["blockId"])):
                        r_saved.append({"area": area, "cur": cur, "tag": tag,
                                        "path": p.refresh_route(None, area, cur, force=True, panels=panels)})
            p.load_lab_state = lambda: None

            expect = {"by_area": by_area, "check": check, "refresh_now": r_now}
            if saved_route:
                expect["refresh_saved"] = r_saved
            if file_ev == evv and file_value:      # 真图 + 同口径 → 把文件里 PC 当年的结论也带上（供核对）
                expect["stored_value"] = file_value
            map_cases.append({
                "input": {"name": name, "panels": panels, "event_value": evv,
                          "saved_route": ({str(k): v for k, v in (saved_route or {}).items()} or None)},
                "expect": expect,
            })
    p.BLOCK_SCORE[5] = 350

    # ── ③ pick_node ───────────────────────────────────────────────────────
    pick_cases = []
    BIDS = [10101, 10201, 10202, 10301, 99001, 99999]
    for _ in range(args.picks):
        nodes = []
        for _i in range(rnd.randint(0, 8)):
            nodes.append({
                "blockId": rnd.choice(BIDS),
                "blockType": rnd.choice([1, 2, 3, 4, 5, 6, 7, 8, None]),
                "panelState": rnd.choice([0, 1, 2, 3, 4, 4, 4, 5]),
                "x": rnd.choice([-10, 0, 100, 960, 1919, 1920, 2000, None]),
                "y": rnd.choice([-5, 0, 540, 1079, 1080, 1200, None]),
            })
        nxt = rnd.choice([[], [], [], [10201, 10202], [10301], [99999], [10101]])
        t = p.pick_node(nodes, nxt)
        pick_cases.append({
            "input": {"nodes": nodes, "next": nxt, "screen_w": 1920, "screen_h": 1080},
            "expect": {"blockId": (t or {}).get("blockId"), "blockType": (t or {}).get("blockType"),
                       "panelState": (t or {}).get("panelState"),
                       "x": (t or {}).get("x"), "y": (t or {}).get("y")},
        })

    out = {
        "meta": {"generated_at": time.strftime("%Y-%m-%d %H:%M:%S"), "pc_dir": pc,
                 "counts": {"score": len(score_cases), "map": len(map_cases), "pick": len(pick_cases)},
                 "note": "基准全部来自 pcr_auto.py 的真函数；真图的 value 是 PC 当年验过的结论"},
        "score_cases": score_cases,
        "map_cases": map_cases,
        "pick_cases": pick_cases,
    }
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print("已写出 %s：探针 %d 条 / 地图 %d 条 / 选点 %d 条"
          % (args.out, len(score_cases), len(map_cases), len(pick_cases)))


if __name__ == "__main__":
    main()
