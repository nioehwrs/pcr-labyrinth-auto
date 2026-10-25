# -*- coding: utf-8 -*-
"""**一键发版**（2026-10-25）：构建 release APK → 生成 latest.json → 传到 Gitee 公开发布仓库。

背景
----
* 源码在 **私有**仓库 `nioehwrs/labyrinth-auto`；
* 更新包在 **公开**仓库 `nioehwrs/labyrinth-auto-release`（App 只认它，不需要任何 token）；
* 应用内自动更新读 `update/latest.json`（raw），APK 走 Release 附件；
* `huabanxie-server`（改名版 frida-server，**不是**官方 frida）单独作为 `frida-<版本>` 这个
  固定 tag 的附件发布 —— 这样升级 frida 不用重发 APK，且它 42MB 的 gz 不占 APK 体积。

用法
----
```
# 只在本地产产物 + latest.json（不联网、不改版本号）—— 平时先跑这个看结果
python tools/release.py --local

# 正式发版：改版本号 → 构建 → 传 Gitee
python tools/release.py --version 0.3.0 --code 8 --upload

# 第一次要把 huabanxie-server 的 gz 传上去（只需一次，之后升级 frida 版本时才再传）
python tools/release.py --frida-only --upload

# 其它
python tools/release.py --local --no-build      # 复用已有 release 产物
python tools/release.py --upload --dry-run      # 打印要发什么，不动线上
python tools/release.py --latest-only           # 只推 update/latest.json（放占位，免得 App 检查 404）
```

需要什么
--------
* `tools/.gitee_token`（一行 token）或环境变量 `GITEE_TOKEN`，权限勾 `projects` 即可；
* `android/keystore.properties` + `keystore/release.keystore`（本来就有）；
* `dist/frida/huabanxie-server-<ver>-<abi>.gz`（本脚本也能自己压：`--pack-frida`）。
"""
import base64
import glob
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))   # labyrinth_auto_android/
ANDROID = os.path.join(ROOT, "android")
GRADLE = os.path.join(ANDROID, "app", "build.gradle.kts")
DIST = os.path.join(ROOT, "dist")
FRIDA_DIR = os.path.join(DIST, "frida")
INSTALLER_KT = os.path.join(
    ANDROID, "app", "src", "main", "java", "com", "lmj", "labyrinthauto", "frida",
    "FridaServerInstaller.kt",
)
TOKEN_FILE = os.path.join(ROOT, "tools", ".gitee_token")

OWNER = "nioehwrs"
REPO = "labyrinth-auto-release"
BRANCH = "master"
API = "https://gitee.com/api/v5"
RAW = "https://gitee.com/%s/%s/raw/%s/%%s" % (OWNER, REPO, BRANCH)
DL = "https://gitee.com/%s/%s/releases/download/%%s/%%s" % (OWNER, REPO)

FRIDA_VERSION = "17.16.4"
FRIDA_ABIS = ["x86_64"]


# ────────────────────────── 小工具 ──────────────────────────
def say(msg):
    print(msg, flush=True)


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def token():
    t = os.environ.get("GITEE_TOKEN", "").strip()
    if t:
        return t
    if os.path.exists(TOKEN_FILE):
        with open(TOKEN_FILE, "r", encoding="utf-8") as f:
            t = f.read().strip()
        if t:
            return t
    return ""


def gradle_versions():
    txt = open(GRADLE, "r", encoding="utf-8").read()
    code = int(re.search(r"versionCode\s*=\s*(\d+)", txt).group(1))
    name = re.search(r'versionName\s*=\s*"([^"]+)"', txt).group(1)
    return code, name


def bump_gradle(code, name):
    txt = open(GRADLE, "r", encoding="utf-8").read()
    new = re.sub(r"versionCode\s*=\s*\d+", "versionCode = %d" % code, txt)
    new = re.sub(r'versionName\s*=\s*"[^"]+"', 'versionName = "%s"' % name, new)
    if new != txt:
        open(GRADLE, "w", encoding="utf-8", newline="\n").write(new)
        say("已更新 build.gradle.kts → versionCode=%d versionName=%s" % (code, name))


def kotlin_frida_consts():
    """读 FridaServerInstaller.kt 里那份 ABI → (rawBytes, rawSha256)，用来核对发布件。"""
    try:
        txt = open(INSTALLER_KT, "r", encoding="utf-8").read()
    except OSError:
        return {}
    out = {}
    for m in re.finditer(r'Asset\("([a-z0-9_]+)",\s*([\d_]+)L,\s*"([0-9a-f]{64})"\)', txt):
        out[m.group(1)] = (int(m.group(2).replace("_", "")), m.group(3))
    return out


def run(cmd, cwd=None, timeout=1800):
    say("$ " + cmd)
    p = subprocess.run(cmd, cwd=cwd, shell=True, timeout=timeout)
    if p.returncode != 0:
        raise SystemExit("命令失败（exit=%d）：%s" % (p.returncode, cmd))


# ────────────────────────── Gitee API（只用标准库） ──────────────────────────
def api(method, path, data=None, query=None):
    q = dict(query or {})
    q["access_token"] = token()
    url = API + path + "?" + urllib.parse.urlencode(q)
    body = None
    headers = {"User-Agent": "lmj-release"}
    if data is not None:
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json;charset=UTF-8"
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            raw = r.read().decode("utf-8", "replace")
            return json.loads(raw) if raw.strip() else {}
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")[:400]
        raise SystemExit("Gitee API %s %s 失败：HTTP %d %s" % (method, path, e.code, detail))


def api_upload(release_id, path):
    """multipart 上传附件（Gitee: POST /releases/{id}/attach_files，字段名 file）"""
    boundary = "----lmj" + uuid.uuid4().hex
    name = os.path.basename(path)
    with open(path, "rb") as f:
        content = f.read()
    parts = []
    for k, v in (("access_token", token()),):
        parts.append(("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n%s\r\n"
                      % (boundary, k, v)).encode("utf-8"))
    parts.append(("--%s\r\nContent-Disposition: form-data; name=\"file\"; filename=\"%s\"\r\n"
                  "Content-Type: application/octet-stream\r\n\r\n" % (boundary, name)).encode("utf-8"))
    parts.append(content)
    parts.append(("\r\n--%s--\r\n" % boundary).encode("utf-8"))
    body = b"".join(parts)
    req = urllib.request.Request(
        "%s/repos/%s/%s/releases/%s/attach_files" % (API, OWNER, REPO, release_id),
        data=body,
        headers={"Content-Type": "multipart/form-data; boundary=%s" % boundary,
                 "User-Agent": "lmj-release"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=1800) as r:
            return json.loads(r.read().decode("utf-8", "replace") or "{}")
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")[:400]
        raise SystemExit("上传附件失败（HTTP %d）：%s\n文件：%s" % (e.code, detail, path))


def ensure_release(tag, name, notes, dry):
    """有就复用、没有就建；返回 (release_id, 已存在的附件文件名集合)"""
    if dry and not token():          # dry-run 要能完全离线跑（没配 token 也不该报错）
        say("[dry-run] 无 token，跳过线上查询；会新建/复用 Release：%s" % tag)
        return None, set()
    rels = api("GET", "/repos/%s/%s/releases" % (OWNER, REPO), query={"per_page": 100})
    for r in rels:
        if r.get("tag_name") == tag:
            assets = {a.get("name") for a in (r.get("assets") or [])}
            say("Release 已存在：%s（附件 %d 个）" % (tag, len(assets)))
            return r.get("id"), assets
    if dry:
        say("[dry-run] 会新建 Release：%s" % tag)
        return None, set()
    r = api("POST", "/repos/%s/%s/releases" % (OWNER, REPO), data={
        "tag_name": tag, "name": name, "body": notes,
        "target_commitish": BRANCH, "prerelease": False,
    })
    say("已新建 Release：%s（id=%s）" % (tag, r.get("id")))
    return r.get("id"), set()


def put_file(path_in_repo, text, message, dry):
    """更新发布仓库里的文件（latest.json）—— Gitee：新建用 POST，更新用 PUT + sha。"""
    if dry and not token():
        say("[dry-run] 无 token，跳过写入 %s" % path_in_repo)
        return
    data = {"access_token": token(), "content": base64.b64encode(text.encode("utf-8")).decode(),
            "message": message}
    try:
        cur = api("GET", "/repos/%s/%s/contents/%s" % (OWNER, REPO, path_in_repo),
                  query={"ref": BRANCH})
        # 文件已存在才回 dict（带 sha）；不存在时 Gitee 可能回 404（抛 SystemExit）或空数组
        if isinstance(cur, dict) and cur.get("sha"):
            data["sha"] = cur["sha"]
            method = "PUT"
        else:
            method = "POST"
    except SystemExit:
        method = "POST"
    if dry:
        say("[dry-run] 会写入 %s（%s）" % (path_in_repo, method))
        return
    api(method, "/repos/%s/%s/contents/%s" % (OWNER, REPO, path_in_repo), data=data)
    say("已更新发布仓库文件：%s" % path_in_repo)


# ────────────────────────── 产物 ──────────────────────────
def pack_frida(force=False):
    """把 assets 里那份 huabanxie-server 压成 gz（assets 已移除时复用 dist/frida 里的留底）。"""
    os.makedirs(FRIDA_DIR, exist_ok=True)
    src = os.path.join(ANDROID, "app", "src", "main", "assets", "frida-server-%s-x86_64" % FRIDA_VERSION)
    made = []
    for abi in FRIDA_ABIS:
        out = os.path.join(FRIDA_DIR, "huabanxie-server-%s-%s.gz" % (FRIDA_VERSION, abi))
        if os.path.exists(out) and not force:
            say("已有：%s（%d 字节）" % (os.path.relpath(out, ROOT), os.path.getsize(out)))
            made.append(out)
            continue
        if not os.path.exists(src):
            raise SystemExit("找不到源文件：%s（--pack-frida 需要它）" % src)
        import gzip
        with open(src, "rb") as fi, gzip.GzipFile(out, "wb", compresslevel=9, mtime=0) as fo:
            shutil.copyfileobj(fi, fo, 1 << 20)
        say("已压缩：%s（%.1f MB）" % (os.path.relpath(out, ROOT), os.path.getsize(out) / 1048576))
        made.append(out)
    return made


def build_apk(version, do_build):
    if do_build:
        run("gradlew.bat assembleRelease -q --console=plain", cwd=ANDROID)
    apk = os.path.join(ANDROID, "app", "build", "outputs", "apk", "release", "app-release.apk")
    if not os.path.exists(apk):
        raise SystemExit("没找到 release APK：%s（先跑 --build）" % apk)
    out = os.path.join(DIST, "labyrinth-auto-%s.apk" % version)
    shutil.copy2(apk, out)
    return out


# ────────────────────────── 主流程 ──────────────────────────
def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    args = sys.argv[1:]
    version = code = None
    do_build = True
    upload = local_only = dry = frida_only = latest_only = False
    pack = False
    i = 0
    while i < len(args):
        a = args[i]
        if a == "--version":
            version = args[i + 1]; i += 2; continue
        if a == "--code":
            code = int(args[i + 1]); i += 2; continue
        if a == "--no-build":
            do_build = False
        elif a == "--local":
            local_only = True
        elif a == "--upload":
            upload = True
        elif a == "--dry-run":
            dry = True
        elif a == "--frida-only":
            frida_only = True
        elif a == "--latest-only":
            latest_only = True
        elif a == "--pack-frida":
            pack = True
        elif a in ("-h", "--help"):
            print(__doc__); return 0
        else:
            raise SystemExit("未知参数：%s（--help 看用法）" % a)
        i += 1

    cur_code, cur_name = gradle_versions()
    if version or code:
        new_code = code if code else cur_code + (1 if version else 0)
        bump_gradle(new_code, version or cur_name)
        cur_code, cur_name = gradle_versions()
    say("当前版本：versionCode=%d versionName=%s" % (cur_code, cur_name))

    if pack:
        pack_frida(force=True)

    # ① frida 发布件（首次要单独传一次）
    frida_files = sorted(glob.glob(os.path.join(FRIDA_DIR, "huabanxie-server-%s-*.gz" % FRIDA_VERSION)))
    kt = kotlin_frida_consts()
    if frida_files:
        say("\n— huabanxie-server 发布件 —")
        for f in frida_files:
            abi = os.path.basename(f).replace("huabanxie-server-%s-" % FRIDA_VERSION, "").replace(".gz", "")
            raw_bytes, raw_sha = _raw_of_gz(f)
            say("  %s：gz %.1fMB ｜ 解压 %d 字节 ｜ raw sha256=%s"
                % (abi, os.path.getsize(f) / 1048576, raw_bytes, raw_sha[:16] + "…"))
            if abi in kt and (kt[abi][0] != raw_bytes or kt[abi][1].lower() != raw_sha.lower()):
                say("  ⚠ 与 FridaServerInstaller.kt 里的常量不一致！Apk 装到设备上会校验失败：")
                say("     Kotlin: %d 字节 / %s" % kt[abi])
                say("     实际  : %d 字节 / %s" % (raw_bytes, raw_sha))
            elif abi not in kt:
                say("  ⚠ Kotlin 里没有这个 abi 的 Asset 条目（真机 arm64 需要补）")

    if frida_only:
        if not frida_files:
            raise SystemExit("dist/frida 里没有发布件（先跑 --pack-frida）")
        return _upload_frida(frida_files, dry)

    # ② APK
    say("\n— APK —")
    apk = build_apk(cur_name, do_build)
    size = os.path.getsize(apk)
    sha = sha256_of(apk)
    say("  %s ｜ %.1f MB ｜ sha256=%s" % (os.path.basename(apk), size / 1048576, sha[:16] + "…"))
    if size > 100 * 1024 * 1024:
        say("  ⚠ 超过 Gitee 附件 100MB 上限，会导致上传失败（现在靠 jniLibs.useLegacyPackaging 压到 ~64MB）")

    # ③ latest.json
    tag = "v%s" % cur_name
    notes = _changelog_head(cur_name)
    frida_block = {}
    for f in frida_files:
        abi = os.path.basename(f).replace("huabanxie-server-%s-" % FRIDA_VERSION, "").replace(".gz", "")
        raw_bytes, raw_sha = _raw_of_gz(f)
        frida_block[abi] = {"url": DL % ("frida-%s" % FRIDA_VERSION, os.path.basename(f)),
                            "size": raw_bytes, "sha256": raw_sha,
                            "gzSize": os.path.getsize(f), "gzSha256": sha256_of(f)}
    latest = {
        "versionCode": cur_code,
        "versionName": cur_name,
        "apkUrl": DL % (tag, os.path.basename(apk)),
        "sha256": sha,
        "size": size,
        "frida": {"version": FRIDA_VERSION, "assets": frida_block},
        "force": False,
        "minVersionCode": 1,
        "channel": "stable",
        "notes": notes,
        "mirrors": [],
        "publishedAt": time.strftime("%Y-%m-%d %H:%M:%S"),
    }
    os.makedirs(DIST, exist_ok=True)
    out_json = os.path.join(DIST, "latest-%s.json" % cur_name)
    with open(out_json, "w", encoding="utf-8", newline="\n") as f:
        json.dump(latest, f, ensure_ascii=False, indent=2)
    say("\n已生成：%s" % os.path.relpath(out_json, ROOT))
    say("App 读的地址：%s" % (RAW % "update/latest.json"))

    # --latest-only：只把 latest.json 推到发布仓库（先放个占位，避免 App 检查更新 404）
    if latest_only:
        if not token():
            raise SystemExit("没有 token：写 tools/.gitee_token 或设环境变量 GITEE_TOKEN")
        put_file("update/latest.json", json.dumps(latest, ensure_ascii=False, indent=2),
                 "更新 latest.json（versionCode %d）" % cur_code, dry=dry)
        say("\n已只更新清单：%s" % (RAW % "update/latest.json"))
        return 0

    if local_only or not upload:
        say("\n（只生成到本地。要发到 Gitee 加 --upload）")
        return 0

    # ④ 上传 APK Release
    if dry:
        say("\n[dry-run] 跳过所有上传")
        return 0
    if not token():
        raise SystemExit("没有 token：写 tools/.gitee_token 或设环境变量 GITEE_TOKEN（权限勾 projects）")
    say("\n— 上传 Release %s —" % tag)
    rid, assets = ensure_release(tag, "labyrinth-auto %s" % cur_name, notes, dry=False)
    if os.path.basename(apk) in assets:
        say("附件已存在，跳过：%s" % os.path.basename(apk))
    else:
        api_upload(rid, apk)
        say("已上传 APK：%s" % os.path.basename(apk))
    put_file("update/latest.json", json.dumps(latest, ensure_ascii=False, indent=2),
             "更新记录 %s（versionCode %d）" % (cur_name, cur_code), dry=False)
    say("\n发布完成：%s" % (RAW % "update/latest.json"))
    return 0


def _upload_frida(files, dry):
    if not dry and not token():
        raise SystemExit("没有 token：写 tools/.gitee_token 或设环境变量 GITEE_TOKEN")
    tag = "frida-%s" % FRIDA_VERSION
    rid, assets = ensure_release(tag, "huabanxie-server %s" % FRIDA_VERSION,
                                 "改名版 frida-server（躲反注入），App 首跑时下载", dry=dry)
    for f in files:
        name = os.path.basename(f)
        if name in assets:
            say("附件已存在，跳过：%s" % name)
            continue
        if dry:
            say("[dry-run] 会传：%s（%.1fMB）" % (name, os.path.getsize(f) / 1048576))
            continue
        api_upload(rid, f)
        say("已上传：%s" % name)
    say("\n下载地址示例：%s" % (DL % (tag, os.path.basename(files[0]))))
    return 0


def _raw_of_gz(path):
    """解压 gz 算解压后大小/摘要（不落盘，流式）"""
    import gzip
    h = hashlib.sha256()
    n = 0
    with gzip.open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
            n += len(chunk)
    return n, h.hexdigest()


def _changelog_head(version):
    """取 CHANGELOG.md 里 `## <version>` 这一节（给 App 的更新说明用）"""
    path = os.path.join(ROOT, "CHANGELOG.md")
    try:
        lines = open(path, "r", encoding="utf-8").read().splitlines()
    except OSError:
        return ""
    out, on = [], False
    for ln in lines:
        if ln.startswith("## "):
            if on:
                break
            on = version in ln
        if on:
            out.append(ln)
    text = "\n".join(out).strip()
    return text[:2000]


if __name__ == "__main__":
    sys.exit(main())
