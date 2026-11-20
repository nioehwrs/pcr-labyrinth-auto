# -*- coding: utf-8 -*-
"""边跑边采内存/CPU（诊断用，2026-11-17）。

用法：
    python tools/mem_watch.py [次数=120] [间隔秒=20]

输出：tools/mem_watch.csv
关注点：
  * app_native_mb 持续上涨 = native 泄漏；
  * load1 接近/超过 CPU 核数 = CPU 打满（lmkd 会以 "device is not responding" 杀进程）；
  * app_cpu_pct / game_cpu_pct = 各自占单核的百分比（4 核满 = 400%）。
"""
import os
import re
import subprocess
import sys
import time

DEV = "127.0.0.1:16480"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mem_watch.csv")
APP = "com.lmj.labyrinthauto"
GAME = "com.bilibili.priconne"


def sh(cmd, timeout=25):
    try:
        r = subprocess.run(["adb", "-s", DEV, "shell", cmd],
                           capture_output=True, text=True,
                           encoding="utf-8", errors="replace", timeout=timeout)
        return r.stdout or ""
    except Exception:
        return ""


def num(pat, txt, g=1):
    m = re.search(pat, txt)
    return int(m.group(g)) if m else -1


def meminfo(pkg):
    """(TOTAL PSS, Native Heap PSS, Java Heap PSS) KB"""
    txt = sh("dumpsys meminfo %s" % pkg)
    return (num(r"TOTAL PSS:\s*(\d+)", txt),
            num(r"Native Heap:\s*(\d+)", txt),
            num(r"Java Heap:\s*(\d+)", txt))


def avail_mb():
    return num(r"MemAvailable:\s*(\d+)", sh("cat /proc/meminfo")) // 1024


def load1():
    t = sh("cat /proc/loadavg").strip().split()
    return float(t[0]) if t else -1.0


def pid_of(pkg):
    t = sh("pidof %s" % pkg).strip()
    return int(t.split()[0]) if t else 0


def cpu_ticks(pid):
    """进程累计 CPU jiffies（utime+stime）；读不到 -1。"""
    if pid <= 0:
        return -1
    parts = sh("cat /proc/%d/stat" % pid).split()
    if len(parts) < 15:
        return -1
    try:
        return int(parts[13]) + int(parts[14])
    except ValueError:
        return -1


def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 120
    gap = int(sys.argv[2]) if len(sys.argv) > 2 else 20
    prev_ap = prev_gp = -1
    prev_t = time.time()
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write("time,app_pss_mb,app_native_mb,app_java_mb,game_pss_mb,mem_avail_mb,"
                "load1,app_cpu_pct,game_cpu_pct\n")
        f.flush()
        for _ in range(n):
            t = time.strftime("%H:%M:%S")
            now = time.time()
            ap, an, aj = meminfo(APP)
            gp = meminfo(GAME)[0]
            av = avail_mb()
            ld = load1()
            ac = cpu_ticks(pid_of(APP))
            gc = cpu_ticks(pid_of(GAME))
            dt = max(now - prev_t, 0.001)
            apct = (ac - prev_ap) / 100.0 / dt * 100.0 if (ac >= 0 and prev_ap >= 0) else -1.0
            gpct = (gc - prev_gp) / 100.0 / dt * 100.0 if (gc >= 0 and prev_gp >= 0) else -1.0
            prev_ap, prev_gp, prev_t = ac, gc, now
            f.write("%s,%.1f,%.1f,%.1f,%.1f,%d,%.2f,%.1f,%.1f\n"
                    % (t, ap / 1024.0, an / 1024.0, aj / 1024.0, gp / 1024.0, av, ld, apct, gpct))
            f.flush()
            print("%s app=%.0fMB native=%.0f java=%.0f game=%.0fMB avail=%dMB "
                  "load=%.2f cpu(app=%.0f%% game=%.0f%%)"
                  % (t, ap / 1024.0, an / 1024.0, aj / 1024.0, gp / 1024.0, av, ld, apct, gpct),
                  flush=True)
            time.sleep(gap)


if __name__ == "__main__":
    main()
