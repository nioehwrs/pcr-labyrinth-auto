"""开发小工具：从 PC 侧调 agent 的任意 rpc 导出函数（用于探 UI / 找按钮坐标）。

用法：
    python agent_call.py <pid> <fn> [JSON 参数...]
例：
    python agent_call.py 1234 viewId
    python agent_call.py 1234 labStatus
    python agent_call.py 1234 findTextList '"黎明界"' 10

注意：
    - 它只加载 agent_base.js（交互式，不发 socket），是"一次性读"，不常驻；
    - 每次调用都会往目标进程里多塞一个 agent 实例（读多写少，安全，但别高频刷）；
    - 注入走 frida-server（安全路径），不走 frida-inject。
"""
import json
import sys
import time
from pathlib import Path

import frida

REMOTE = '127.0.0.1:1234'
# 相对本脚本解析（原来写死 d:\Users\zhouyi\... 的旧路径，换机器/换盘就失效）
BASE = str(Path(__file__).resolve().parent.parent / 'android' / 'app' / 'src' / 'main' / 'assets' / 'agent_base.js')


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__)
        return 1
    pid = int(sys.argv[1])
    fn = sys.argv[2]
    args = [json.loads(a) for a in sys.argv[3:]]
    src = open(BASE, encoding='utf-8').read()
    # 注意：导出名不能带前导下划线（frida 会把 __probe 变成 Probe 导致找不到），
    # 另外别用 base 里已有的名字（find/tree/...）
    src += '\nrpc.exports.zzprobe = function (fn, args) { return rpc.exports[fn].apply(null, args || []); };\n'

    dev = frida.get_device_manager().add_remote_device(REMOTE)
    sess = dev.attach(pid)
    script = sess.create_script(src)
    script.on('message', lambda m, d: print('MSG:', str(m)[:300]))
    script.load()
    for i in range(60):          # 等 il2cpp
        try:
            if script.exports_sync.ready():
                break
        except Exception:
            pass
        time.sleep(0.5)
    try:
        result = script.exports_sync.zzprobe(fn, args)
        print(json.dumps(result, ensure_ascii=False, indent=2)[:8000])
    except Exception as e:
        print('调用出错:', type(e).__name__, e)
    finally:
        sess.detach()
    return 0


if __name__ == '__main__':
    sys.exit(main())
