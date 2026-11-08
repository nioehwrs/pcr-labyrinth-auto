"""开发小工具：特别装备三连页（EX）的**一致性/耗时**探针。

在**同一个 agent 实例**里按 app 的调用序列交替读，验证：
  · 轻量签名读（labExEquipSig）与整包读（labExEquip）给出的 sig 是否一致
  · 两者的耗时（整包读要不要坐标：full=true/false）

用法：
    python tools/exeq_probe.py <pid> [轮数]
"""
import json
import os
import sys
import time

import frida

REMOTE = os.environ.get('LMJ_FIDA_REMOTE', '127.0.0.1:1237')
BASE = r'd:\Users\zhouyi\Documents\LMJ\labyrinth_auto_android\android\app\src\main\assets\agent_base.js'


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    pid = int(sys.argv[1])
    rounds = int(sys.argv[2]) if len(sys.argv) > 2 else 5
    src = open(BASE, encoding='utf-8').read()
    src += '\nrpc.exports.zzprobe = function (fn, args) { return rpc.exports[fn].apply(null, args || []); };\n'

    dev = frida.get_device_manager().add_remote_device(REMOTE)
    sess = dev.attach(pid)
    script = sess.create_script(src)
    script.on('message', lambda m, d: print('MSG:', str(m)[:300]))
    script.load()
    for _ in range(60):
        try:
            if script.exports_sync.ready():
                break
        except Exception:
            pass
        time.sleep(0.5)

    rows = []
    cases = [('sig', 'labExEquipSig', []), ('all', 'labExEquip', [True]),
             ('nocoord', 'labExEquip', [False]),
             ('TwoBtnRight', 'labExEquip', [True, 'TwoButtonRight']),
             ('prio0', 'labExEquip', [True, 'prio0']),
             ('check', 'labExEquip', [True, 'check']),
             ('rare', 'labExEquip', [True, 'rare'])]
    try:
        for name, fn, args in cases:
            times = []
            last = None
            for _ in range(rounds):
                t0 = time.time()
                try:
                    r = script.exports_sync.zzprobe(fn, args)
                except Exception as e:
                    r = {'err': str(e)}
                times.append(int((time.time() - t0) * 1000))
                last = r
            rows.append({'name': name, 'ms': times, 'sig': (last or {}).get('sig'),
                         'top': (last or {}).get('top')})
            print('%-14s %s ms   sig=%s' % (name, times, (last or {}).get('sig')))
    finally:
        sess.detach()

    out = r'd:\Users\zhouyi\Documents\LMJ\labyrinth_auto_android\tools\lmj_exeq_probe.json'
    with open(out, 'w', encoding='utf-8') as f:
        json.dump(rows, f, ensure_ascii=False, indent=2)
    print('-> wrote', out)
    return 0


if __name__ == '__main__':
    sys.exit(main())
