"""P1 外部注入器：用设备上的 frida-server 把 agent（agent_base.js + agent_bridge.js）
注入游戏进程，然后 eternalize（脚本留在游戏里继续跑，PC 可以关掉）。

为什么不放 app 里注入：见 `android/app/src/main/java/com/lmj/labyrinthauto/frida/FridaInjector.kt`
里 SELF_INJECT 的注释 —— 实测这个游戏吃 frida-inject 会被反作弊打死（EXIT_SELF），
而走 frida-server 这条注入路径则毫发无损。

用法：
    python inject_agent.py              # 自动找 com.bilibili.priconne 的 pid
    python inject_agent.py 12345        # 指定 pid

前置：
    - 设备侧 frida-server 在跑（本机是 /data/local/tmp/huabanxie-base，监听 1234）
    - PC 侧装了 frida（pip）
    - app 已经打开并点了「启动」（这样 127.0.0.1:27183 的 socket 服务端在听）
"""
import os
import sys
import time

import frida

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.normpath(os.path.join(HERE, '..', 'android', 'app', 'src', 'main', 'assets'))
TARGET = 'com.bilibili.priconne'
REMOTE = '127.0.0.1:1234'
READY_WAIT_S = 120


def build_source() -> str:
    with open(os.path.join(ASSETS, 'agent_base.js'), encoding='utf-8') as f:
        base = f.read()
    with open(os.path.join(ASSETS, 'agent_bridge.js'), encoding='utf-8') as f:
        bridge = f.read()
    return base + '\n' + bridge


def on_message(message, data):
    # agent 的 console.log（含 [bridge] 的日志）都会走到这里
    print('MSG:', str(message)[:300])


def main() -> int:
    device = frida.get_device_manager().add_remote_device(REMOTE)

    # 可选：--stay N  注入后保持连接 N 秒，把 agent 的 console 日志（含 [bridge]）实时打出来
    args = sys.argv[1:]
    stay = 0
    if '--stay' in args:
        i = args.index('--stay')
        stay = int(args[i + 1])
        del args[i:i + 2]

    if args:
        pid = int(args[0])
    else:
        procs = [p for p in device.enumerate_processes() if p.name == TARGET]
        if not procs:
            print('没找到目标进程:', TARGET)
            return 2
        pid = procs[0].pid
    print('attach pid =', pid)

    session = device.attach(pid)
    script = session.create_script(build_source())
    script.on('message', on_message)
    script.load()
    print('脚本已加载，等 il2cpp 就绪…')

    ready = False
    for i in range(READY_WAIT_S):
        try:
            if script.exports_sync.ready():
                print('il2cpp 就绪（等了 %ds）' % i)
                ready = True
                break
        except Exception as e:       # 初始化过程中取不到属正常，继续等
            if i % 10 == 0:
                print('  ready() 还不行:', e)
        time.sleep(1)
    if not ready:
        print('警告：等 %ds 还没就绪，仍然 eternalize（agent 会自己继续初始化）' % READY_WAIT_S)

    if stay:
        print('保持连接 %ds，观察 agent 的 console 日志（[bridge] 那些）…' % stay)
        for i in range(stay):
            time.sleep(1)
            if (i + 1) % 3 == 0:
                print('  …已观察 %ds' % (i + 1), flush=True)
        print('观察结束，准备 eternalize')

    script.eternalize()
    session.detach()
    print('已 eternalize 并断开：agent 留在游戏里，会自己连回 app 的 127.0.0.1:27183')
    return 0


if __name__ == '__main__':
    sys.exit(main())
