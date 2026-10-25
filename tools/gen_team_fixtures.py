#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
P4 对拍的**用例生成器**（PC 侧）：跑 PC 版 `team_build`，把 (输入 → 期望输出) 落成 JSON。

为什么由 PC 侧生成：`PLAN.md §5` 的 P4 验收就是"Kotlin 输出与 Python **完全一致**"，
基准只能来自 PC 版；生成完，APK 侧拿它做 **JVM 单元测试**（不用设备，`gradlew test` 即可）。

用例来源：
  ① `team_runs.jsonl` 里**真实跑过**的轮次（真实池子/关卡，最有价值）——
     ★只用它的**输入**，期望输出由**当前代码**重算（别信日志里那会儿的输出：`C` 权重可能已改过）；
  ② 合成：随机池子 × 全部关卡档 × 图数 1~5 × 遗物技能档（0/400/800），覆盖边角；
     含"池里没有 dot 角色""池子极窄""属性限定制"等**失败/退化路径**。

输出（默认 `tests/fixtures/team_fixtures.json`）：
  {
    "meta": {"generated_at":…, "py_hash":…, "tables": {"units.json": md5, …}, "count": N},
    "cases": [ {"input": {stage,pool,relic_skill,floor,size,failed}, "expect": {…}}, … ]
  }

用法：
    python tools/gen_team_fixtures.py --pc "D:\\Users\\zhouyi\\Documents\\LMJ\\labyrinth_auto" --random 300
"""
import argparse
import hashlib
import json
import os
import random
import sys
import time

TABLES = ("units.json", "unit_tags.json", "tag_defs.json")


def md5(path):
    try:
        with open(path, "rb") as f:
            return hashlib.md5(f.read()).hexdigest()
    except OSError:
        return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pc", required=True, help="PC 版工程目录（含 team_build.py 与三张表）")
    ap.add_argument("--out", default=os.path.join("tests", "fixtures", "team_fixtures.json"))
    ap.add_argument("--random", type=int, default=300, help="合成用例数量")
    ap.add_argument("--seed", type=int, default=20261014)
    ap.add_argument("--max-log", type=int, default=4000, help="单条 log/warn 截断长度")
    args = ap.parse_args()

    pc = os.path.abspath(args.pc)
    sys.path.insert(0, pc)
    import team_build as tb  # noqa: E402  （必须先把 --pc 放进 sys.path）

    U, TAGS, DEFS = tb.load()
    all_uids = [str(k) for k in U.keys()]
    stages = sorted(tb.PROFILES.keys())
    rnd = random.Random(args.seed)

    cases = []
    seen = set()

    def add(stage, pool, relic_skill, floor, size=None, failed=False, tag=""):
        pool = [str(x) for x in pool]
        key = (str(stage), tuple(sorted(pool)), int(relic_skill), int(floor), size, bool(failed))
        if key in seen:
            return False
        seen.add(key)
        res = tb.build_best(stage, pool, relic_skill, floor, size, failed=failed)
        # plan() 是给 pcr_auto 的正式契约（站位顺序的 unitId 列表）—— 必须与 build_best 同一输入
        order = tb.plan(stage, pool, relic_skill, floor, size, failed=failed)

        def clip(v):
            if isinstance(v, str) and len(v) > args.max_log:
                return v[: args.max_log] + "…(截断)"
            if isinstance(v, list):
                return [clip(x) for x in v]
            return v

        cases.append({
            "input": {
                "stage": str(stage), "pool": pool, "relic_skill": int(relic_skill),
                "floor": int(floor), "size": size, "failed": bool(failed), "from": tag,
            },
            "expect": {
                "plan": order,
                "team": clip(res.get("team")),
                "picked": clip(res.get("picked")),
                "reasons": clip(res.get("reasons")),
                "types": clip(res.get("types")),
                "attrs": clip(res.get("attrs")),
                "attr_bonus": res.get("attr_bonus"),
                "score": res.get("score"),
                "fail": clip(res.get("fail")),
                "log": clip(res.get("log")),
                "warn": clip(res.get("warn")),
            },
        })
        return True

    # ① 真实运行过的输入（重算期望）
    n_real = 0
    runs_path = os.path.join(pc, "team_runs.jsonl")
    if os.path.exists(runs_path):
        with open(runs_path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    r = json.loads(line)
                except Exception:
                    continue
                if r.get("type") != "run":
                    continue
                pool = [p.get("id") for p in (r.get("pool") or []) if p.get("id")]
                if not pool:
                    continue
                if add(r.get("stage") or r.get("algo") or "normal", pool,
                       r.get("relic_skill") or 0, r.get("floor") or 1, None, False, "real-run"):
                    n_real += 1

    # ② 合成：覆盖各关卡档 × 图数 × 遗物技能 × 不同池子规模/性质
    n_rand = 0
    while n_rand < args.random:
        stage = rnd.choice(stages)
        size_choice = rnd.choice([12, 16, 20, 26, 34, 45])
        pool = rnd.sample(all_uids, size_choice)
        floor = rnd.choice([1, 2, 3, 4, 5])
        relic_skill = rnd.choice([0, 0, 400, 800])
        failed = rnd.random() < 0.15
        if add(stage, pool, relic_skill, floor, None, failed, "random"):
            n_rand += 1

    # ③ 退化/边界：空池、单角色、只抽到坦克、只抽到 dot、同属性池
    def uids_with(tag_id, limit=40):
        out = [u for u in all_uids if tag_id in tb.tags_of(TAGS, u)]
        return out[:limit] if limit else out

    add("dot_only", [], 0, 1, None, False, "edge-empty-pool")
    add("normal", all_uids[:1], 0, 1, None, False, "edge-single")
    tanks = [u for u in all_uids if tb.T["tank"] in tb.tags_of(TAGS, u)]
    if tanks:
        add("normal", tanks[:12], 0, 4, None, False, "edge-tanks-only")
    dots = [u for u in all_uids if tb.dot_kinds_of(TAGS, u)]
    if dots:
        add("dot_only", dots[:12], 0, 1, None, False, "edge-dots-only")
        add("dot_only", dots[:2], 0, 5, None, True, "edge-dots-narrow-failed")
    for attr in ("火", "水", "风", "光", "暗"):
        same = [u for u in all_uids if (U[u].get("attr") == attr)]
        if len(same) >= 12:
            add(rnd.choice(stages), same[:12], 0, rnd.choice([1, 5]), None, False, "edge-same-attr-" + attr)

    out = {
        "meta": {
            "generated_at": time.strftime("%Y-%m-%d %H:%M:%S"),
            "pc_dir": pc,
            "py_hash": md5(os.path.join(pc, "team_build.py")),
            "tables": {t: md5(os.path.join(pc, t)) for t in TABLES},
            "count": len(cases),
            "real": n_real,
            "random": n_rand,
            "seed": args.seed,
        },
        "cases": cases,
    }
    outp = os.path.abspath(args.out)
    os.makedirs(os.path.dirname(outp), exist_ok=True)
    with open(outp, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))

    # ── 探针（第二层：纯函数/常量，逐项对）─────────────────────────────────────
    # 目的：让 Kotlin 侧的移植**能分层验证** —— 全链路 `build` 是几百行的移植，
    # 一上来只能"全对/全错"；有了探针就能先把"读表 + 标签/评级/系别 + profile"钉死，
    # 再往上叠算法，出问题知道是哪一层。
    scens = ["all", "sutong", "dot_only", "burst", "aoe"]
    units = {}
    for uid in sorted(U.keys()):
        units[str(uid)] = {
            "tags": sorted(tb.tags_of(TAGS, uid)),
            "tier": {s: tb.tier_of(TAGS, uid, s) for s in scens},
            "eff_atk": tb.eff_atk(U, TAGS, uid),
            "dot_kinds": sorted(tb.dot_kinds_of(TAGS, uid)),
        }
    profs = {}
    for st in stages:
        for fl in (1, 2, 3, 4, 5):
            profs["%s|%d" % (st, fl)] = tb._apply_floor(tb.profile_of(st), fl)
    probes = {
        "meta": {"generated_at": out["meta"]["generated_at"], "py_hash": out["meta"]["py_hash"]},
        "units": units,
        "profiles": profs,
        # 常量表也钉一下（Kotlin 侧逐字抄完要对得上）
        "consts": {"T": tb.T, "DOT_KINDS": list(tb.DOT_KINDS), "FIX_TAGS": tb.FIX_TAGS,
                   "T_DOT": tb.T_DOT, "T_DOT_SUP": tb.T_DOT_SUP,
                   "SIZE": tb.C.SIZE, "SKILL_CUT": tb.C.SKILL_CUT, "ATTR_MIN": tb.C.ATTR_MIN,
                   "ATTR_TOL": tb.C.ATTR_TOL, "ATTR_W": tb.C.ATTR_W, "BOSSWEAK": tb.C.BOSSWEAK,
                   "TANK_EXTRA": tb.C.TANK_EXTRA, "RANGE_SPREAD_PENALTY": tb.C.RANGE_SPREAD_PENALTY,
                   "ATTR_FORCE": tb.C.ATTR_FORCE, "W": tb.C.W},
        "profile_keys": sorted(stages),
    }
    ppath = os.path.join(os.path.dirname(outp), "team_probes.json")
    with open(ppath, "w", encoding="utf-8") as f:
        json.dump(probes, f, ensure_ascii=False, separators=(",", ":"))

    print("用例总数 %d（真实 %d ｜ 合成 %d ｜ 边界若干）→ %s（%.1f KB）"
          % (len(cases), n_real, n_rand, outp, os.path.getsize(outp) / 1024.0))
    print("探针：角色 %d 个 ｜ profile×floor %d 组 → %s（%.1f KB）"
          % (len(units), len(profs), ppath, os.path.getsize(ppath) / 1024.0))
    print("team_build.py md5 = %s" % out["meta"]["py_hash"])
    # 顺手报一下失败用例数（dot_only 无 dot 之类，Kotlin 侧也要复现同样的 fail）
    nfail = sum(1 for c in cases if c["expect"].get("fail"))
    print("其中 expect.fail 非空（要复现失败路径）的用例 %d 条" % nfail)


if __name__ == "__main__":
    main()
