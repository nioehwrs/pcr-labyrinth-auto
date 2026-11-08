"""开发小工具（临时）：从 PC 侧调 agent 的任意 rpc 导出函数。

与 tools/agent_call.py 的区别：**端口可配**（REMOTE 环境变量），
便于避开 PC 本机端口被占用的情况。

用法：
    python tools/agent_call2.py <pid> <fn> [JSON 参数...]
例：
    python tools/agent_call2.py 23072 labExEquip true
"""
import json
import os
import sys
import time

import frida

REMOTE = os.environ.get('LMJ_FIDA_REMOTE', '127.0.0.1:1237')
BASE = r'd:\Users\zhouyi\Documents\LMJ\labyrinth_auto_android\android\app\src\main\assets\agent_base.js'


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__)
        return 1
    pid = int(sys.argv[1])
    fn = sys.argv[2]
    args = []
    for a in sys.argv[3:]:
        try:
            args.append(json.loads(a))
        except Exception:
            args.append(a)          # ★不是 JSON 就当普通字符串（"prio0" 这种）
    src = open(BASE, encoding='utf-8').read()
    src += '\nrpc.exports.zzprobe = function (fn, args) { return rpc.exports[fn].apply(null, args || []); };\n'

    dev = frida.get_device_manager().add_remote_device(REMOTE)
    sess = dev.attach(pid)
    script = sess.create_script(src)
    script.on('message', lambda m, d: print('MSG:', str(m)[:300]))
    script.load()
    for i in range(60):
        try:
            if script.exports_sync.ready():
                break
        except Exception:
            pass
        time.sleep(0.5)
    try:
        result = script.exports_sync.zzprobe(fn, args)
        out = r'd:\Users\zhouyi\Documents\LMJ\labyrinth_auto_android\tools\lmj_probe.json'
        with open(out, 'w', encoding='utf-8') as f:
            json.dump(result, f, ensure_ascii=False, indent=2)
        print('→ 已写', out)
    except Exception as e:
        print('调用出错:', type(e).__name__, e)
    finally:
        sess.detach()
    return 0


if __name__ == '__main__':
    sys.exit(main())
