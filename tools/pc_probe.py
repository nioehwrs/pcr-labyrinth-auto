#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
P2 对拍（PC 侧）：对**同一个游戏进程**调同一组 agent 导出，打印 `PROBE <fn> <json>`。

为什么需要它：APK 版跑 `AgentApi.probeAll()`（结果进 logcat），PC 版跑这个脚本（结果进终端），
两边在**同一页面、同一时刻**各调一遍，然后 `tools/probe_diff.py` 逐条比对 ——
这正是 `PLAN.md §5` 里 P2 的验收标准："能调任意导出函数拿 JSON，与 PC 版逐项对照一致"。

用法：
    python tools/pc_probe.py --js "D:\\Users\\zhouyi\\Documents\\LMJ\\labyrinth_auto\\perception_patched.js"
    # 结果存文件再到本目录比对：
    python tools/probe_diff.py --app app_probe.txt --pc pc_probe.txt

要点：
  * 走**设备上的 frida-server**（默认 127.0.0.1:1234），和 APK 版共用同一个 server；
  * 只 attach + 调 rpc，不改游戏；结束时 unload 脚本并 detach（不留第二个 agent 在游戏里）；
  * 调用名按 frida python 的约定转成 snake_case（JS `labStatus` ↔ Python `lab_status`）。
"""
import argparse
import json
import re
import subprocess
import sys
import time

# ★与 app 侧 AgentApi.PROBE_CALLS 保持一致（改动请两边一起改）
CALLS = [
    ("ready", []),
    ("jsBuild", []),
    ("viewId", []),
    ("screen", []),
    ("dialog", []),
    ("labStatus", []),
    ("labNodes", []),
    ("labMap", []),
    ("labEvent", []),
    ("labSelection", []),
    ("labChoices", []),
    ("labShop", []),
    ("labResult", []),
    ("labOwnRelics", []),
    ("labUnits", []),
    ("labParty", [False]),
    ("labExEquip", [False]),
    ("labExEquipTop", []),
    ("labQuestDetail", ["UIButton_Unit"]),
    ("labChallenging", []),
    ("labCharaPageQuick", []),
    ("findList", ["Button"]),
    ("findTextList", ["Button", 5]),
]


def snake(name):
    """labCharaPageQuick -> lab_chara_page_quick（frida python 的命名约定）"""
    return re.sub(r"(?<!^)(?=[A-Z])", "_", name).lower()


def find_pid(args):
    """
    找游戏主进程的 pid。两步，顺序很重要：

    ① frida 的 `enumerate_processes()` —— **在 MuMu 上对游戏主进程无效**（实测 2026-10-14：
       游戏 pid 还在，枚举里只有 `com.bilibili.priconne:pushservice`；主进程被隔离了）；
    ② 所以兜底用 `adb shell pidof`（= PC 版 `pcr_auto.py` 的做法，它一直用 pidof 拿 pid）。
    """
    pid = 0
    try:
        dev = frida_dev(args)
        for p in dev.enumerate_processes():
            if p.name == args.pkg:
                pid = p.pid
                break
    except Exception as e:
        print("[probe] 枚举进程失败（继续用 adb pidof）：%s" % e, file=sys.stderr)

    if not pid:
        try:
            out = subprocess.run([args.adb, "shell", "pidof", args.pkg],
                                 capture_output=True, text=True, timeout=20).stdout.strip()
            for tok in out.split():
                if tok.isdigit():
                    pid = int(tok)
                    break
        except Exception as e:
            print("[probe] adb pidof 失败：%s" % e, file=sys.stderr)
    return pid


def frida_dev(args):
    import frida
    return frida.get_device_manager().add_remote_device(args.addr)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--addr", default="127.0.0.1:1234")
    ap.add_argument("--pkg", default="com.bilibili.priconne")
    ap.add_argument("--pid", type=int, default=0)
    ap.add_argument("--js", required=True, help="PC 版 agent（perception_patched.js）路径")
    ap.add_argument("--ready-timeout", type=float, default=60.0)
    ap.add_argument("--adb", default="adb", help="adb 可执行文件（默认 PATH 里的）")
    args = ap.parse_args()

    import frida  # 放到这里 import：没装 frida 时也能看 --help

    dev = frida.get_device_manager().add_remote_device(args.addr)
    pid = args.pid or find_pid(args)
    if not pid:
        print("[probe] 找不到进程 %s（可用 --pid 指定）" % args.pkg, file=sys.stderr)
        return 2

    print("[probe] attach %s → pid %d" % (args.addr, pid))
    session = dev.attach(pid)
    with open(args.js, "r", encoding="utf-8") as f:
        src = f.read()
    script = session.create_script(src)
    script.on("message", lambda m, d: print("[agent] %s" % m))
    script.load()
    ex = script.exports_sync

    # 等 il2cpp 就绪（与 APK 版同样的判据：agent 的 ready 导出）
    t0 = time.time()
    ready = False
    while time.time() - t0 < args.ready_timeout:
        try:
            if ex.ready():
                ready = True
                break
        except Exception as e:
            print("[probe] ready 调用异常：%s" % e, file=sys.stderr)
        time.sleep(0.5)
    print("[probe] ready=%s（等了 %.1fs）" % (ready, time.time() - t0))

    for fn, fargs in CALLS:
        try:
            r = getattr(ex, snake(fn))(*fargs)
            line = json.dumps(r, ensure_ascii=False)
        except Exception as e:
            line = json.dumps({"__call_failed__": str(e)}, ensure_ascii=False)
        print("PROBE %s %s" % (fn, line))
        sys.stdout.flush()

    try:
        script.unload()
    except Exception:
        pass
    try:
        session.detach()
    except Exception:
        pass
    print("[probe] 已卸载脚本并 detach")
    return 0


if __name__ == "__main__":
    sys.exit(main())
