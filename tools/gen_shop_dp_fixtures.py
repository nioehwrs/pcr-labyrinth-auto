#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 `ShopDp`（Android 精确 DP）的对拍基准 → `tests/fixtures/shop_dp_fixtures.json`。

对拍的两边**故意不同源**：

  · 基准侧（本脚本）：`gen_shop_dp.Model` 的真函数 `w` / `v` / `f` —— 直接用 Python 对象算，
    **不经过 JSON 曲线**。
  · 被测侧（Kotlin `ShopDp.decide`）：读 `assets/shop_curves.json` 的 G/H 曲线 + 运行时枚举子集。

这样一趟能同时验证：① 曲线打包（含 4 位小数舍入）没算错；② Kotlin 的二分/掩码/刷新费写对了。

跑法：`python tools/gen_shop_dp_fixtures.py`
"""

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_shop_dp import (  # noqa: E402
    DISCS, MULTI, Model, counts_of, shelf,
)

# 覆盖「一分钱都没有 / 刚好够一件 / 刚好够刷新 / 钱很多」几档
MONEY_FRACS = [0.0, 0.35, 0.62, 0.85, 1.0, 1.15, 1.4, 1.8, 2.4, 3.2]


def cases_for(b_count):
    model = Model(b_count)
    model.build_g()
    model.build_h()
    out = []
    for disc in DISCS:
        for remaining in (2, 1, 0):
            for stars, _ in MULTI:
                items = shelf(stars, disc, b_count)
                total = sum(p for p, _ in items)
                for f in MONEY_FRACS:
                    money = int(round(total * f / 100.0)) * 100      # 取整到百，金币都是整百
                    if remaining >= 2:
                        score, switched, mask, cost, _ = model.f(stars, money, disc)
                    elif remaining == 1:
                        score, switched, mask, cost, _ = model.v(stars, money, disc)
                    else:
                        score, cost, mask = model.w(stars, money, disc)
                        switched = False
                    n1, n2, n3, nb = counts_of(mask, stars, b_count)
                    kinds = list(stars) + [0] * b_count      # 0 = 印记
                    out.append({
                        "b": b_count, "disc": disc, "remaining": remaining, "money": money,
                        "items": [
                            {"price": items[i][0], "score": items[i][1], "kind": kinds[i]}
                            for i in range(len(items))
                        ],
                        "expect": {
                            "n1": n1, "n2": n2, "n3": n3, "nb": nb,
                            "refresh": 1 if switched else 0,
                            "cost": cost,
                        },
                    })
    return out


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out_path = os.path.join(root, "tests", "fixtures", "shop_dp_fixtures.json")
    payload = {"cases": cases_for(2) + cases_for(3)}
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, separators=(",", ":"))
    n = len(payload["cases"])
    print(f"[ok] {out_path}  {n} 例  {os.path.getsize(out_path) / 1024:.1f} KB")


if __name__ == "__main__":
    sys.exit(main())
