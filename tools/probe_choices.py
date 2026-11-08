# -*- coding: utf-8 -*-
"""临时探针：把 `labChoices(force=true)` 的**完整返回**打出来（排查"取不到决定坐标"）。

用法：python tools/probe_choices.py <pid>
"""
import json
import os
import sys
import time

import frida

REMOTE = os.environ.get('LMJ_FIDA_REMOTE', '127.0.0.1:1237')
BASE = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                    'android', 'app', 'src', 'main', 'assets', 'agent_base.js')

PROBE = r'''
rpc.exports.zzdump = function (fn, args) {
  try { return JSON.stringify(rpc.exports[fn].apply(null, args || [])); }
  catch (e) { return 'ERR:' + e; }
};
'''


def main() -> int:
    pid = int(sys.argv[1])
    src = open(BASE, encoding='utf-8').read() + '\n' + PROBE
    dev = frida.get_device_manager().add_remote_device(REMOTE)
    sess = dev.attach(pid)
    script = sess.create_script(src)
    script.on('message', lambda m, d: None)
    script.load()
    for _ in range(60):
        try:
            if script.exports_sync.ready():
                break
        except Exception:
            pass
        time.sleep(0.5)

    for name, fn, args in [('top', 'labTop', []),
                           ('selection', 'labSelection', [True]),
                           ('choices', 'labChoices', [True])]:
        t0 = time.time()
        raw = script.exports_sync.zzdump(fn, args)
        ms = int((time.time() - t0) * 1000)
        print('--- %s (%dms) ---' % (name, ms))
        try:
            print(json.dumps(json.loads(raw), ensure_ascii=False, indent=1)[:3000])
        except Exception:
            print(str(raw)[:3000])
    sess.detach()
    return 0


if __name__ == '__main__':
    sys.exit(main())
