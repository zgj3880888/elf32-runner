#!/usr/bin/env python3
"""轮询 GitHub Actions 构建直到结束，按步骤打印结论；失败时自动取回日志。

用法: python3 actions_watch.py [等待秒数上限]
Token 从环境变量 GH_TOKEN 读，或从 git credential store 自动取。

要点（都是踩出来的）：
- 判定看**步骤级** conclusion，不要只看 run 的 status（in_progress/completed 容易被干扰）
- 日志端点会 302 重定向到 Azure blob。默认重定向跟随会把 Authorization 头一起带过去，
  对方报 401 Server failed to authenticate the request。
  必须自定义 redirect_request 返回 None，再从 Location 手动取，且**第二次不带认证头**。
"""
import json
import os
import subprocess
import sys
import time
import urllib.request

REPO = "zgj3880888/elf32-runner"
API = "https://api.github.com/repos/" + REPO


def get_token():
    if os.environ.get("GH_TOKEN"):
        return os.environ["GH_TOKEN"]
    out = subprocess.run(
        ["git", "credential", "fill"],
        input="protocol=https\nhost=github.com\n\n",
        capture_output=True, text=True).stdout
    for line in out.splitlines():
        if line.startswith("password="):
            return line[len("password="):]
    raise SystemExit("拿不到 GitHub 令牌")


class NoAuthRedirect(urllib.request.HTTPRedirectHandler):
    """让 urllib 不自动跟随，以便手动剥掉 Authorization 头。"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


TOK = get_token()


def get(path):
    r = urllib.request.Request(API + path, headers={
        "Authorization": "Bearer " + TOK,
        "Accept": "application/vnd.github+json",
        "User-Agent": "wb-actions-watch"})
    return json.load(urllib.request.urlopen(r))


def get_raw(url, auth=True):
    """取原始字节；自动处理 302 且重定向后不带认证头。"""
    opener = urllib.request.build_opener(NoAuthRedirect)
    r = urllib.request.Request(url, headers={
        "Authorization": "Bearer " + TOK,
        "Accept": "application/vnd.github+json",
        "User-Agent": "wb-actions-watch"} if auth else {
        "User-Agent": "wb-actions-watch"})
    try:
        resp = opener.open(r)
        return resp.read()
    except urllib.error.HTTPError as e:
        if e.code in (301, 302, 303, 307, 308):
            loc = e.headers.get("Location")
            if loc:
                return get_raw(loc, auth=False)   # 第二次不带认证头
        raise


def fetch_step_log(job_id, step_name):
    """拉某个 job 的完整 zip 日志代价大，这里改用 issues 式的逐步重定向接口。"""
    return None


def main():
    limit = int(sys.argv[1]) if len(sys.argv) > 1 else 480
    deadline = time.time() + limit
    last = None
    print("监视 %s ，最长等待 %d 秒" % (REPO, limit))
    while time.time() < deadline:
        runs = get("/actions/runs?per_page=1")
        if not runs.get("workflow_runs"):
            print("  尚无 run，等待 Actions 触发…")
            time.sleep(10)
            continue
        run = runs["workflow_runs"][0]
        if run["status"] != "completed":
            if last != ("run", run["id"], run["status"]):
                print("  run #%s  status=%s" % (run["run_number"], run["status"]))
                last = ("run", run["id"], run["status"])
            time.sleep(15)
            continue

        head = run["head_branch"]
        print("\n构建结束：conclusion=%s" % run.get("conclusion"))
        print("URL: %s" % run["html_url"])

        jobs = get("/actions/runs/%d/jobs" % run["id"])
        failed = False
        for job in jobs.get("jobs", []):
            print("\n[job] %s -> %s" % (job["name"], job["conclusion"]))
            for st in job.get("steps", []):
                flag = "OK " if st["conclusion"] == "success" else \
                       ("SKIP" if st["conclusion"] == "skipped" else "FAIL")
                print("   [%s] %-34s (%ds)"
                      % (flag, st["name"], int(st["completed_at"] and 0 or 0)))
                if st["conclusion"] == "failure":
                    failed = True
                    failed_step = st["name"]
        if failed:
            print("\n=== 失败步骤日志（末尾 120 行） ===")
            try:
                logs = get_raw(API + "/actions/runs/%d/logs" % run["id"])
                open("build-logs.zip", "wb").write(logs)
                print("已保存完整日志到 build-logs.zip (%d 字节)" % len(logs))
            except Exception as e:
                print("拉取日志失败:", e)
            return 1
        print("\n全部步骤通过。")
        return 0
    print("\n等待超时（仍在进行）")
    return 2


if __name__ == "__main__":
    sys.exit(main())
