"""连常驻 daemon 发一条命令（不 attach 游戏）。

    python tools/frida_cli.py labPartySlots
    python tools/frida_cli.py labExEquipSig
    python tools/frida_cli.py labPartyPress '["remove",[102501]]' --poll
    python tools/frida_cli.py labExPress '["sort:魔法防御贯穿"]' --poll
    python tools/frida_cli.py --quit
"""
import argparse
import json
import socket
import sys

PORT = 12777


def main() -> int:
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass
    ap = argparse.ArgumentParser()
    ap.add_argument('fn', nargs='?', default='', help='RPC 名')
    ap.add_argument('args', nargs='?', default='[]', help='参数 JSON 数组')
    ap.add_argument('--poll', action='store_true', help='两段式：投递后轮询到完成')
    ap.add_argument('--timeout', type=float, default=30.0)
    ap.add_argument('--port', type=int, default=PORT)
    ap.add_argument('--quit', action='store_true', help='让 daemon 退出（同样不 attach 游戏）')
    a = ap.parse_args()

    req = {'fn': a.fn, 'args': json.loads(a.args or '[]'),
           'poll': a.poll, 'timeout': a.timeout}
    if a.quit:
        req = {'fn': 'ready', 'args': [], 'timeout': 5}

    s = socket.create_connection(('127.0.0.1', a.port), timeout=a.timeout + 5)
    try:
        s.sendall((json.dumps(req, ensure_ascii=False) + '\n').encode('utf-8'))
        buf = b''
        while b'\n' not in buf:
            chunk = s.recv(65536)
            if not chunk:
                break
            buf += chunk
        txt = buf.decode('utf-8', 'replace').strip()
        try:
            print(json.dumps(json.loads(txt), ensure_ascii=False, indent=2))
        except Exception:
            print(txt)
        return 0
    finally:
        s.close()


if __name__ == '__main__':
    sys.exit(main())
