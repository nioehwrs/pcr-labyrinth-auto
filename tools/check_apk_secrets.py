# -*- coding: utf-8 -*-
"""发版前扫一遍包：确认**不该进包的口令**确实没进去。

背景（2026-11-01 用户）：App 里只需要"反馈小号"的令牌（对发布仓库只有观察者权限，改不了 Release）；
**开发/发布用的令牌绝不能进包**，否则一个反编译就能换掉线上 APK。签名口令同理。

用法（默认扫 release 产物）：
```
python tools/check_apk_secrets.py
python tools/check_apk_secrets.py --apk dist/labyrinth-auto-0.4.3.apk
```

口径：
* **必须出现**：反馈小号令牌（`tools/.gitee_token_issue`）—— 不出现说明反馈通道没编进去；
* **必须不出现**：`tools/.gitee_token`（发布令牌）、keystore 的 storePassword / keyPassword；
* 顺带确认 `gitee.properties` 没有被当成资源打进包（它是构建输入，不是 asset）。

扫描是流式的（4MB 分块 + 128 字节重叠），116MB 的 libfrida_bridge.so 也能扫，不会吃内存。
"""
import argparse
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_APK = os.path.join(ROOT, "android/app/build/outputs/apk/release/app-release.apk")

CHUNK = 4 << 20
OVERLAP = 128


def _lines(path):
    if not os.path.exists(path):
        return []
    with open(path, encoding="utf-8") as f:
        return [l.strip() for l in f if l.strip() and not l.strip().startswith("#")]


def _one(path):
    lines = _lines(path)
    return lines[0] if lines else ""


def _prop(path, key):
    for l in _lines(path):
        if "=" in l and l.split("=", 1)[0].strip() == key:
            return l.split("=", 1)[1].strip()
    return ""


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", default=DEFAULT_APK)
    args = ap.parse_args()

    if not os.path.exists(args.apk):
        raise SystemExit("找不到 APK：%s（先 assembleRelease）" % args.apk)

    must_have = {"反馈小号 token": _one(os.path.join(ROOT, "tools/.gitee_token_issue"))}
    must_not = {
        "发布/开发 token（tools/.gitee_token）": _one(os.path.join(ROOT, "tools/.gitee_token")),
        "keystore storePassword": _prop(os.path.join(ROOT, "android/keystore.properties"), "storePassword"),
        "keystore keyPassword": _prop(os.path.join(ROOT, "android/keystore.properties"), "keyPassword"),
    }
    must_have = {k: v for k, v in must_have.items() if v}
    must_not = {k: v for k, v in must_not.items() if v}
    if not must_have:
        raise SystemExit("没读到反馈小号令牌文件，无法判断")

    hits = {k: set() for k in list(must_have) + list(must_not)}
    names = []
    big = []
    with zipfile.ZipFile(args.apk) as z:
        names = z.namelist()
        for info in z.infolist():
            if info.is_dir():
                continue
            if info.file_size > 50 << 20:
                big.append((info.filename, info.file_size))
            with z.open(info) as f:
                tail = b""
                while True:
                    buf = f.read(CHUNK)
                    if not buf:
                        break
                    data = tail + buf
                    for k, v in must_have.items():
                        if v.encode() in data:
                            hits[k].add(info.filename)
                    for k, v in must_not.items():
                        if v.encode() in data:
                            hits[k].add(info.filename)
                    tail = data[-OVERLAP:]

    print("APK：%s（%.1f MB，%d 个条目）" % (args.apk, os.path.getsize(args.apk) / 1048576, len(names)))
    for name, size in big:
        print("  · 大文件也扫了：%s（%.1f MB）" % (name, size / 1048576))

    bad = 0
    for k in must_have:
        where = sorted(hits[k])
        if where:
            print("  ✓ 应包含 %s → %s" % (k, ", ".join(where)))
        else:
            bad += 1
            print("  ✗ 没找到 %s —— 反馈通道是不是没编进去？" % k)
    for k in must_not:
        where = sorted(hits[k])
        if where:
            bad += 1
            print("  ✗ **不该出现** %s → %s" % (k, ", ".join(where)))
        else:
            print("  ✓ 不含 %s" % k)
    if any("gitee.properties" in n for n in names):
        bad += 1
        print("  ✗ 包里出现了 gitee.properties")
    else:
        print("  ✓ 包里没有 gitee.properties")

    print("结论：%s" % ("有问题" if bad else "干净"))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
