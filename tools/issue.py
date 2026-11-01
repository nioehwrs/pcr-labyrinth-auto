# -*- coding: utf-8 -*-
"""**提 issue / 跟 issue / 传诊断包**（全部用"反馈小号"的令牌）。

用法
----
```
# 看现有 issue（**不需要 token**：仓库公开，匿名可读）
python tools/issue.py list
python tools/issue.py list --state all --limit 50

# 提新 issue（需要 token）
python tools/issue.py new --title "0.4.2 在 5 层商店卡住" --body D:\\logs\\report.md --labels bug,反馈

# 带诊断包：先传到附件仓库的 feedback Release，再把链接拼进正文
python tools/issue.py new --title "..." --body D:\\logs\\report.md --attach D:\\Downloads\\diag_xxx.zip

# 追加回复 / 关闭 / 重新打开（issue 号是字符串，形如 IKS1NB）
python tools/issue.py comment IKS1NB --body D:\\logs\\add.md
python tools/issue.py close IKS1NB
python tools/issue.py reopen IKS1NB
```

账号与令牌（**按"令牌已经泄露"来设计**）
----------------------------------------
| 用途 | 账号 | 文件 | scope |
|---|---|---|---|
| 提 issue + 传诊断包 | 反馈小号（`the-oceans-gentle-giant`） | `tools/.gitee_token_issue` | `projects`（必须）+ `issues`（建议） |

* 小号对发布仓库 `nioehwrs/labyrinth-auto-release` 只有 **观察者（pull）** 权限：
  实测 `POST /repos/nioehwrs/labyrinth-auto-release/releases` → **403**，改不了线上 APK。
* 诊断包传到**小号自己的** `labyrinth-auto-feedback` 的 `feedback` Release（它 own，能写）。
* ⚠**绝不能用主号/发布令牌**：那个令牌要随 App 分发 = 等于公开，主号带 admin，
  泄露就能换掉线上 Release。脚本不做任何"回退到发布令牌"的事（缺令牌直接报错）。

Gitee API 的坑（2026-11-01 实测，别再踩）
----------------------------------------
1. **建 issue 的路径里没有 repo 段**：`POST /repos/{owner}/issues`，`repo` 走**表单**参数。
   按直觉写成 `POST /repos/{owner}/{repo}/issues` 会一直
   `404 {"message":"Not Found Project"}`（我在这上面绕了半天）。
2. **issue 号是字符串**（`IKS1NB` 这种），不是数字；`close/comment` 都收字符串。
3. 关闭/重开：`PATCH /repos/{owner}/issues/{number}` + 表单 `{repo, state}`。
4. 评论：`POST /repos/{owner}/{repo}/issues/{number}/comments`（这个路径**带** repo）；
   issue 处于关闭状态时会被拒：`401 当前issue已关闭评论功能`。
5. Release 附件属于 `projects` 权限；建 Release 需要 `target_commitish`（分支名），
   且**仓库必须先有提交**（空仓库建 tag 会报 `创建标签失败`）。
6. Issue 正文的附件 API 传不了 —— 所以诊断包走 Release 附件 + 正文贴链接。
"""
import argparse
import base64
import datetime
import json
import os
import shutil
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

API = "https://gitee.com/api/v5"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# ① 提 issue：发布仓库（公开）。小号只是"观察者"，够提 issue、改不了 Release。
OWNER = "nioehwrs"
REPO = "labyrinth-auto-release"
TOKEN_FILE = os.path.join(ROOT, "tools", ".gitee_token_issue")

# ② 诊断包：小号自己的仓库（它 own ⇒ 能建 Release / 传附件）。
FB_OWNER = "the-oceans-gentle-giant"
FB_REPO = "labyrinth-auto-feedback"
FB_TAG = "feedback"
FB_TOKEN_FILE = os.path.join(ROOT, "tools", ".gitee_token_feedback")   # 可选，默认与 issue 同号

ISSUES = "/repos/%s/issues" % OWNER                       # 建 issue / 改状态（repo 走表单）
REPO_ISSUES = "/repos/%s/%s/issues" % (OWNER, REPO)       # 列表 / 评论


def _read_token(env, path, what, hint):
    t = os.environ.get(env, "").strip()
    if t:
        return t
    if os.path.exists(path):
        with open(path, "r", encoding="utf-8") as fh:
            t = fh.read().strip()
        if t:
            return t
    raise SystemExit("没有%s令牌：写进 %s（一行），或设环境变量 %s。\n%s"
                     % (what, os.path.relpath(path, ROOT), env, hint))


def token(required=True):
    """提 issue 的令牌（反馈小号）。**故意不回退**到发布/开发令牌（那会越权）。"""
    try:
        return _read_token("GITEE_ISSUE_TOKEN", TOKEN_FILE, " issue ",
                           "生成位置：小号 → 设置 → 私人令牌（勾 projects，建议同时勾 issues）。")
    except SystemExit:
        if required:
            raise
        return ""


def fb_token():
    """传诊断包用的令牌：默认复用 issue 令牌（同一个反馈小号），也可用单独文件覆盖。"""
    t = os.environ.get("GITEE_FEEDBACK_TOKEN", "").strip()
    if not t and os.path.exists(FB_TOKEN_FILE):
        with open(FB_TOKEN_FILE, "r", encoding="utf-8") as fh:
            t = fh.read().strip()
    return t or token()


def _req(method, path, tk="", fields=None, query=None, timeout=120):
    """Gitee v5 的写接口按 swagger 是 formData ⇒ 一律表单编码（JSON 也能过，但表单最稳）。"""
    q = dict(query or {})
    if tk:
        q["access_token"] = tk
    url = API + path + ("?" + urllib.parse.urlencode(q) if q else "")
    body = urllib.parse.urlencode(fields, doseq=True).encode("utf-8") if fields else None
    headers = {"User-Agent": "lmj-issue"}
    if body:
        headers["Content-Type"] = "application/x-www-form-urlencoded;charset=UTF-8"
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode("utf-8", "replace")
            return json.loads(raw) if raw.strip() else {}
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")[:400]
        raise SystemExit("Gitee API %s %s 失败：HTTP %d %s" % (method, path, e.code, detail))


def call(method, path, data=None, query=None, need_token=True):
    return _req(method, path, token(required=need_token), data, query)


def read_body(args):
    if args.body:
        with open(args.body, "r", encoding="utf-8") as f:
            return f.read()
    return args.body_text or ""


def attach_links(paths):
    """把诊断包传成附件仓库 `feedback` Release 的附件，返回要拼进正文的 markdown。

    Issue 正文的附件 API 传不了，而 Release 附件有 API、下载链接稳定 ⇒ 用 Release 当文件箱。
    文件名统一加时间戳（同名会互相顶掉），上传完删掉临时副本。
    """
    if not paths:
        return ""
    tk = fb_token()
    base_rel = "/repos/%s/%s/releases" % (FB_OWNER, FB_REPO)
    rid = None
    for r in _req("GET", base_rel, tk, query={"per_page": 100}):
        if r.get("tag_name") == FB_TAG:
            rid = r.get("id")
            break
    if rid is None:
        r = _req("POST", base_rel, tk, fields={
            "tag_name": FB_TAG, "name": "反馈附件（诊断包）",
            "body": "用户反馈用的诊断包：由 tools/issue.py --attach 自动上传。仅作文件箱，不是发行版。",
            "target_commitish": "master",
        })
        rid = r.get("id")
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    out = ["", "### 诊断包", ""]
    for p in paths:
        if not os.path.exists(p):
            raise SystemExit("诊断包不存在：%s" % p)
        base, ext = os.path.splitext(os.path.basename(p))
        name = "%s_%s%s" % (base, stamp, ext)
        tmp = os.path.join(os.environ.get("TEMP", ROOT), "_lmj_attach_" + name)
        shutil.copyfile(p, tmp)          # 用临时副本改名，不动用户的原文件
        try:
            res = _upload_multipart(rid, tmp, name, tk)
        finally:
            try:
                os.remove(tmp)
            except OSError:
                pass
        url = res.get("browser_download_url") or (
            "https://gitee.com/%s/%s/releases/download/%s/%s"
            % (FB_OWNER, FB_REPO, FB_TAG, urllib.parse.quote(name))
        )
        out.append("- [%s](%s)" % (name, url))
        print("已上传诊断包：%s" % url)
    out.append("")
    return "\n".join(out)


def _upload_multipart(release_id, path, name, tk):
    """Gitee: POST /releases/{id}/attach_files，字段名 file（multipart）。"""
    boundary = "----lmj" + uuid.uuid4().hex
    with open(path, "rb") as f:
        content = f.read()
    body = b"".join([
        ("--%s\r\nContent-Disposition: form-data; name=\"access_token\"\r\n\r\n%s\r\n"
         % (boundary, tk)).encode("utf-8"),
        ("--%s\r\nContent-Disposition: form-data; name=\"file\"; filename=\"%s\"\r\n"
         "Content-Type: application/octet-stream\r\n\r\n" % (boundary, name)).encode("utf-8"),
        content,
        ("\r\n--%s--\r\n" % boundary).encode("utf-8"),
    ])
    req = urllib.request.Request(
        "%s/repos/%s/%s/releases/%s/attach_files" % (API, FB_OWNER, FB_REPO, release_id),
        data=body,
        headers={"Content-Type": "multipart/form-data; boundary=%s" % boundary,
                 "User-Agent": "lmj-issue"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=1800) as r:
            return json.loads(r.read().decode("utf-8", "replace") or "{}")
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")[:400]
        raise SystemExit("上传诊断包失败（HTTP %d）：%s" % (e.code, detail))


def show(it):
    print("#%s [%s] %s\n  %s" % (it.get("number"), it.get("state"), it.get("title"),
                                 it.get("html_url")))


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    ap = argparse.ArgumentParser(add_help=True)
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("list", help="列 issue（不需要 token）")
    p.add_argument("--state", default="open",
                   choices=["open", "progressing", "closed", "rejected", "all"])
    p.add_argument("--limit", type=int, default=20)

    p = sub.add_parser("new", help="提新 issue（要 token）")
    p.add_argument("--title", required=True)
    p.add_argument("--body", help="正文文件路径（.md/.txt）")
    p.add_argument("--body-text", help="正文直接写在命令行")
    p.add_argument("--labels", help="逗号分隔，如 bug,反馈")
    p.add_argument("--assignee", help="指派给谁（用户名，可空）")
    p.add_argument("--attach", nargs="+", metavar="FILE",
                   help="诊断包 zip（可多个）：传到附件仓库当文件箱，链接自动拼进正文")

    p = sub.add_parser("comment", help="给 issue 追加回复（要 token）")
    p.add_argument("number", help="issue 号（字符串，形如 IKS1NB）")
    p.add_argument("--body", help="正文文件路径")
    p.add_argument("--body-text", help="正文直接写在命令行")
    p.add_argument("--attach", nargs="+", metavar="FILE", help="诊断包 zip（可多个）")

    for name, helptext in (("close", "关闭"), ("reopen", "重新打开")):
        p = sub.add_parser(name, help="%s issue（要 token）" % helptext)
        p.add_argument("number", help="issue 号（字符串，形如 IKS1NB）")

    args = ap.parse_args()

    if args.cmd == "list":
        items = call("GET", REPO_ISSUES, query={"state": args.state, "per_page": args.limit},
                     need_token=False)
        if not items:
            print("（没有 issue）")
        for it in items:
            show(it)
        return

    if args.cmd == "new":
        body = read_body(args)
        if not body.strip():
            raise SystemExit("正文为空：给 --body <文件> 或 --body-text")
        body += attach_links(getattr(args, "attach", None))
        # ★路径只有 {owner}，repo 走表单 —— 写成 /repos/{owner}/{repo}/issues 会 404
        fields = {"repo": REPO, "title": args.title, "body": body}
        if args.labels:
            fields["labels"] = args.labels
        if args.assignee:
            fields["assignee"] = args.assignee
        it = call("POST", ISSUES, data=fields)
        print("已创建 issue：")
        show(it)
        return

    if args.cmd == "comment":
        body = read_body(args)
        if not body.strip():
            raise SystemExit("正文为空：给 --body <文件> 或 --body-text")
        body += attach_links(getattr(args, "attach", None))
        c = call("POST", "%s/%s/comments" % (REPO_ISSUES, args.number), data={"body": body})
        print("已回复 #%s：%s" % (args.number, (c or {}).get("html_url", "")))
        return

    state = "closed" if args.cmd == "close" else "open"
    it = call("PATCH", "%s/%s" % (ISSUES, args.number), data={"repo": REPO, "state": state})
    print("已置为 %s：" % state)
    show(it)


if __name__ == "__main__":
    main()
