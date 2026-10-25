#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
P2 迷宫内对拍「一键」串联器（补 PLAN.md §5 的 P2 🔶）。

背景：标题页那次 22/22 只是"迷宫 RPC 在标题页都返回一致的 null/空" —— 它们的**真实入迷宫解析**
（labStatus 带 panel、labSelection 带卡片、labMap/labShop/labEvent/labChoices/labResult 等）从未在
"有数据"时和 PC 交叉验证过。本脚本把这件事收口成一条命令。

前置（人工）：
  1. 模拟器/真机已 root（优先 `adb root`；否则脚本自动退到 `su 0`）；
  2. 游戏**已经进迷宫**（停在任意迷宫页）；
  3. 本 app 已注入 Frida（agent 已回连）。前台服务由本脚本自动拉起。

流程（脚本自动）：
  A. PC 侧：跑 `tools/pc_probe.py` 加载 PC 版 agent（`perception_patched.js`）对同一个游戏进程
     逐个调导出，结果存 `pc_probe.txt`；
  B. App 侧：先 `adb root`（不行退 `su 0`）拉起**前台服务**（`ACTION_START`，否则 ACTION_PROBE 拉起的
     后台服务会被系统秒杀、写不完文件），再发 `ACTION_PROBE`，`AgentApi.probeAll` 把结果写进 app 私有
     目录 `files/probe_app.txt`；
  C. 轮询等到 `probe_app.txt` 写完（看 size 稳定），用 `run-as` 拉不出来就 `su 0 cat` 兜底；
     轮询时若 stat 拿不到数字（文件还没生成），会把远端真实返回打出来做诊断，不再静默空转；
  D. 跑 `tools/probe_diff.py --app probe_app.txt --pc pc_probe.txt` 逐条比对并打印汇总。

注意：本设备的 `su` 是 toybox，**不支持 `su 0 -c "..."`**（会报 "Cannot execute -c"），所以用
`adb root` 或 `su 0 <命令>`（无 -c）。

用法：
  python tools/probe_maze.py --js "D:\\Users\\zhouyi\\Documents\\LMJ\\labyrinth_auto\\perception_patched.js"
  python tools/probe_maze.py --js ... -s 127.0.0.1:7555   # 多设备指定序列号
  python tools/probe_maze.py --js ... --pc-only            # 只跑 PC 侧
  python tools/probe_maze.py --app-file pc_probe.txt        # 复用已有 pc_probe.txt

退出码：0 = 全部一致；1 = 有差异/失败；2 = 环境或输入问题。
"""
import argparse
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))

APP_PKG = "com.lmj.labyrinthauto"
GAME_PKG = "com.bilibili.priconne"
SERVICE = "%s/.service.AutoForegroundService" % APP_PKG
ACTION_START = "%s.action.START" % APP_PKG
ACTION_PROBE = "%s.action.PROBE" % APP_PKG
PROBE_FILE_REL = "files/probe_app.txt"
PROBE_FILE_ABS = "/data/data/%s/%s" % (APP_PKG, PROBE_FILE_REL)


def run(cmd, quiet=False, **kw):
    if not quiet:
        print("[maze] $ %s" % " ".join(cmd))
    return subprocess.run(cmd, **kw)


def ensure_rooted(adb):
    """尝试 `adb root` 提权；成功（shell 变 uid=0）则返回 True，否则退回 su 0。"""
    run(adb + ["root"], quiet=True, capture_output=True, text=True)
    time.sleep(1.0)
    v = run(adb + ["shell", "id"], quiet=True, capture_output=True, text=True)
    out = (v.stdout or "") + (v.stderr or "")
    if "uid=0" in out:
        return True
    print("[maze] adb root 不可用（id 非 root），改用 su 0。")
    return False


def shell(adb, rooted, *cmd):
    """构造一条 adb shell 命令：rooted 时直接 shell，否则 `su 0 <cmd>`（toybox su 无 -c）。"""
    if rooted:
        return adb + ["shell"] + list(cmd)
    return adb + ["shell", "su", "0"] + list(cmd)


def rm_remote_probe(adb, rooted):
    """删除 app 私有的旧 probe_app.txt —— 保证随后拉到的一定是本次新写的。"""
    print("[maze] 删除远端旧 probe_app.txt…")
    run(shell(adb, rooted, "rm", "-f", PROBE_FILE_ABS), quiet=True, capture_output=True, text=True)


def _start_service(adb, rooted, action):
    """拉起本 app 的 service 并带指定 action（非 exported，靠 root）。"""
    cmd = shell(adb, rooted, "am", "startservice", "-n", SERVICE, "-a", action)
    r = run(cmd, capture_output=True, text=True)
    out = (r.stdout or "") + (r.stderr or "")
    bad = ("Error" in out or "not exported" in out or "Exception" in out
           or "Cannot execute" in out or "Permission" in out or "not allowed" in out)
    if bad:
        print("[maze] 启动失败：%s" % out.strip()[:200])
        return False
    return True


def ensure_service(adb, rooted):
    """步骤 B 前置：先把前台服务拉起来。"""
    print("[maze] 拉起前台服务（ACTION_START）…")
    if not _start_service(adb, rooted, ACTION_START):
        print("[maze] 前台服务启动失败；请先在 app 里点「启动」再跑本脚本。", file=sys.stderr)
        return False
    time.sleep(3.0)
    return True


def trigger_app_probe(adb, rooted):
    """步骤 B：发 ACTION_PROBE。"""
    print("[maze] 触发 app ACTION_PROBE…")
    return _start_service(adb, rooted, ACTION_PROBE)


def pull_app_probe(adb, rooted, out_path, timeout=180.0):
    """步骤 C：轮询等 app 写完 probe_app.txt，再用 run-as / su 拉出来。"""
    t0 = time.time()
    last_size = -1
    stable_for = 0.0
    diag_printed = False
    while time.time() - t0 < timeout:
        # 远端旧文件已在触发前删掉 ⇒ 只要它出现，就一定是本次新写的
        r = run(shell(adb, rooted, "stat", "-c", "'%s'", PROBE_FILE_ABS),
                quiet=True, capture_output=True, text=True)
        line = (r.stdout or "").strip()
        if line and line[0].isdigit():
            size = int(line.split()[0])
            if size > 0 and size == last_size:
                stable_for += 1.0
                if stable_for >= 2.0:
                    break
            else:
                stable_for = 0.0
                last_size = size
            time.sleep(1.0)
        else:
            if not diag_printed:
                print("[maze] stat 暂未拿到数字，远端返回：%r" % ((r.stdout or r.stderr or "")).strip()[:160])
                diag_printed = True
            time.sleep(1.0)
        if int(time.time() - t0) > 0 and int(time.time() - t0) % 5 == 0:
            print("[maze] 等待 probe_app.txt 生成…（%ds）" % int(time.time() - t0))
    else:
        print("[maze] 等 app 写 probe_app.txt 超时（%ds）；前台服务是否在跑？agent 是否回连？" % int(timeout),
              file=sys.stderr)
        return False

    getters = [adb + ["exec-out", "run-as", APP_PKG, "cat", PROBE_FILE_REL]]
    if not rooted:
        getters.append(adb + ["exec-out", "su", "0", "cat", PROBE_FILE_ABS])
    else:
        getters.append(adb + ["exec-out", "cat", PROBE_FILE_ABS])
    for getter in getters:
        r = run(getter, capture_output=True)
        if r.returncode == 0 and r.stdout:
            with open(out_path, "wb") as f:
                f.write(r.stdout)
            print("[maze] App 侧探针已拉到 %s（%d 字节）" % (out_path, os.path.getsize(out_path)))
            return True
    print("[maze] 拉取 probe_app.txt 失败（run-as 与 su 都失败）。", file=sys.stderr)
    return False


def pc_probe(js, addr, adb, out_path, pkg):
    """步骤 A：运行 pc_probe.py，把 PROBE 行落盘到 out_path。"""
    py = os.path.join(HERE, "pc_probe.py")
    proc = run([sys.executable, py, "--js", js, "--addr", addr, "--pkg", pkg],
               capture_output=True, text=True)
    if proc.returncode != 0:
        print(proc.stderr, file=sys.stderr)
        print(proc.stdout)
        return False
    with open(out_path, "w", encoding="utf-8") as f:
        f.write(proc.stdout)
    print("[maze] PC 侧探针已写 %s（%d 字节）" % (out_path, os.path.getsize(out_path)))
    return True


def diff(app_file, pc_file):
    """步骤 D：调用 probe_diff.py。"""
    py = os.path.join(HERE, "probe_diff.py")
    if not os.path.exists(app_file) or not os.path.exists(pc_file):
        print("[maze] 缺少比对输入：app=%s pc=%s" % (app_file, pc_file), file=sys.stderr)
        return 2
    r = run([sys.executable, py, "--app", app_file, "--pc", pc_file])
    return r.returncode


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--js", help="PC 版 agent（perception_patched.js）路径；--pc-only/--app-file 时可省")
    ap.add_argument("--addr", default="127.0.0.1:1234")
    ap.add_argument("--adb", default="adb")
    ap.add_argument("-s", "--serial", default=None, help="adb 设备序列号（多设备时必填）")
    ap.add_argument("--game-pkg", default=GAME_PKG)
    ap.add_argument("--app-pkg", default=APP_PKG)
    ap.add_argument("--out-dir", default=HERE, help="pc_probe.txt / probe_app.txt 输出目录")
    ap.add_argument("--pc-only", action="store_true", help="只跑 PC 侧探针并落盘，不碰 app")
    ap.add_argument("--app-file", help="复用已有的 pc_probe.txt（只拉 app 侧 + 比对）")
    args = ap.parse_args()

    adb = [args.adb] + (["-s", args.serial] if args.serial else [])
    rooted = ensure_rooted(adb)

    pc_path = os.path.join(args.out_dir, "pc_probe.txt")
    app_path = os.path.join(args.out_dir, "probe_app.txt")

    # ── 步骤 A：PC 侧 ──
    if args.app_file:
        if not os.path.exists(args.app_file):
            print("[maze] --app-file 指定的文件不存在：%s" % args.app_file, file=sys.stderr)
            return 2
        pc_path = args.app_file
        print("[maze] 复用已有 PC 探针：%s" % pc_path)
    else:
        if not args.js:
            print("[maze] 需要 --js（PC 版 agent 路径），或改用 --app-file 复用。", file=sys.stderr)
            return 2
        if not pc_probe(args.js, args.addr, adb, pc_path, args.game_pkg):
            return 2
        if args.pc_only:
            print("[maze] --pc-only：PC 侧已落盘 %s，app 侧请手动在 app 里点「PROBE 对拍」后另跑比对。" % pc_path)
            return 0

    # ── 步骤 B：确保前台服务在跑，删旧文件，再触发探针 ──
    if not ensure_service(adb, rooted):
        return 2
    rm_remote_probe(adb, rooted)
    if os.path.exists(app_path):
        os.remove(app_path)
    if not trigger_app_probe(adb, rooted):
        print("[maze] 触发失败；请直接在 app 里点「PROBE 对拍」按钮，然后本脚本用 --app-file 复用。",
              file=sys.stderr)
        time.sleep(3.0)

    # ── 步骤 C：拉 app 探针 ──
    if not pull_app_probe(adb, rooted, app_path):
        return 2

    # ── 步骤 D：比对 ──
    return diff(app_path, pc_path)


if __name__ == "__main__":
    sys.exit(main())
