# -*- coding: utf-8 -*-
"""
百万级导入/导出性能基准测试。
通过任务自身 startedAt/finishedAt 计算纯异步处理耗时，吞吐量 = 行数 / 处理耗时。

用法：
    # 导出（基于已有 100 万学生数据）
    python3 scripts/perf_bench.py --mode export --runs 3 --label exp-pool2

    # 导入（需先启动对应 worker 配置的应用，并指定 100 万行文件）
    python3 scripts/perf_bench.py --mode import --file /tmp/perf_PERF16.xlsx --runs 1 --label imp-worker16
"""
import argparse, json, os, subprocess, sys, time, datetime, re

BASE = os.environ.get("BASE_URL", "http://127.0.0.1:18089")
OWNER = "perf-bench"
AUTH_TOKEN = os.environ.get("API_SECURITY_DEMO_USER_TOKEN", "").strip()

def curl_json(method, path, headers=None, data=None, form_file=None, timeout=600):
    cmd = ["curl", "-sS", "-X", method, "-w", "\n__CODE__%{http_code}"]
    headers = dict(headers or {})
    if AUTH_TOKEN:
        headers.setdefault("Authorization", "Bearer " + AUTH_TOKEN)
    if data is not None and not form_file and not any(k.lower() == "content-type" for k in headers):
        headers["Content-Type"] = "application/json"
    for k, v in headers.items():
        cmd += ["-H", "%s: %s" % (k, v)]
    if form_file:
        cmd += ["-F", "file=@%s" % form_file]
    if data is not None:
        cmd += ["-d", data]
    cmd += ["-m", str(timeout), BASE + path]
    p = subprocess.run(cmd, capture_output=True, text=True)
    out = p.stdout
    code = ""
    if "__CODE__" in out:
        body, code = out.rsplit("__CODE__", 1)
        code = code.strip()
    else:
        body = out
    try:
        j = json.loads(body)
    except Exception:
        return code, {"_raw": body, "_err": p.stderr}
    # 统一响应体 {success,code,message,data}：成功时解包 data，失败时保留信封便于排查
    if isinstance(j, dict) and "data" in j and ("success" in j or "code" in j):
        if j.get("success") is True:
            d = j.get("data")
            return code, (d if isinstance(d, dict) else {})
        return code, j
    return code, j

def parse_dt(v):
    """解析 LocalDateTime：ISO 字符串或 Jackson 数组 [y,m,d,h,m,s,ns]。"""
    if v is None:
        return None
    if isinstance(v, (list, tuple)):
        nums = [int(x) for x in v]
        while len(nums) < 7:
            nums.append(0 if len(nums) < 6 else 0)
        y, mo, d, h, mi, s = nums[0], nums[1], nums[2], nums[3], nums[4], nums[5]
        ns = nums[6] if len(nums) > 6 else 0
        return datetime.datetime(y, mo, d, h, mi, s) + datetime.timedelta(microseconds=ns // 1000)
    if isinstance(v, str):
        s = v.replace(" ", "T")
        s = re.sub(r"(\.\d+)\+\d\d:\d\d$", r"\1", s)
        s = re.sub(r"\+\d\d:\d\d$", "", s)
        for fmt in ("%Y-%m-%dT%H:%M:%S.%f", "%Y-%m-%dT%H:%M:%S"):
            try:
                return datetime.datetime.strptime(s, fmt)
            except ValueError:
                continue
    return None

def wait_terminal(path, headers, max_iter=120, sleep=2):
    last = None
    for _ in range(max_iter):
        code, j = curl_json("GET", path, headers=headers)
        last = j
        st = j.get("status")
        if st in ("SUCCESS", "FAILED", "CANCELED"):
            return j
        time.sleep(sleep)
    return last

def bench_export(runs):
    results = []
    for i in range(runs):
        t0 = time.time()
        code, j = curl_json("POST", "/api/excel/export", headers={"X-User-Id": OWNER})
        tid = j.get("taskId", "")
        if not tid:
            print("  导出提交失败:", code, j); continue
        fin = wait_terminal("/api/excel/export/%s" % tid, {"X-User-Id": OWNER}, max_iter=150, sleep=3)
        wall = time.time() - t0
        ca = parse_dt(fin.get("createdAt")); fa = parse_dt(fin.get("finishedAt"))
        exported = fin.get("exported", 0)
        proc = (fa - ca).total_seconds() if (ca and fa) else None
        rec = {"run": i + 1, "taskId": tid, "status": fin.get("status"),
               "exported": exported, "procSec": round(proc, 2) if proc else None,
               "wallSec": round(wall, 2), "created": fin.get("createdAt"), "finished": fin.get("finishedAt")}
        if proc:
            rec["throughput"] = int(exported / proc)
        results.append(rec)
        print("  run%d  status=%s  exported=%s  处理=%.2fs  wall=%.1fs  吞吐=%s 行/s" % (
            i + 1, rec["status"], exported, proc or 0, wall, rec.get("throughput", "-")))
    return results

def bench_import(file_path, runs):
    results = []
    for i in range(runs):
        t0 = time.time()
        code, j = curl_json("POST", "/api/excel/import", headers={"X-User-Id": OWNER},
                            form_file=file_path, timeout=1800)
        tid = j.get("taskId", "")
        if not tid:
            print("  导入提交失败:", code, j); continue
        fin = wait_terminal("/api/excel/import/%s" % tid, {"X-User-Id": OWNER}, max_iter=150, sleep=2)
        wall = time.time() - t0
        sa = parse_dt(fin.get("startedAt")); fa = parse_dt(fin.get("finishedAt"))
        imported = fin.get("completedCount", 0) or fin.get("totalCount", 0)
        proc = (fa - sa).total_seconds() if (sa and fa) else None
        rec = {"run": i + 1, "taskId": tid, "status": fin.get("status"),
               "imported": imported, "procSec": round(proc, 2) if proc else None,
               "wallSec": round(wall, 2), "errorMessage": fin.get("errorMessage")}
        if proc:
            rec["throughput"] = int(imported / proc)
        results.append(rec)
        print("  run%d  status=%s  imported=%s  处理=%.2fs  wall=%.1fs  吞吐=%s 行/s  %s" % (
            i + 1, rec["status"], imported, proc or 0, wall, rec.get("throughput", "-"),
            ("err=" + rec["errorMessage"]) if rec.get("errorMessage") else ""))
    return results

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=["export", "import"], required=True)
    ap.add_argument("--file", help="导入文件路径（import 模式必填）")
    ap.add_argument("--runs", type=int, default=1)
    ap.add_argument("--label", default="")
    args = ap.parse_args()
    print("=== 基准测试 [%s] label=%s runs=%d ===" % (args.mode, args.label, args.runs))
    if args.mode == "export":
        res = bench_export(args.runs)
    else:
        if not args.file:
            print("import 模式需要 --file"); sys.exit(1)
        res = bench_import(args.file, args.runs)
    oks = [r for r in res if r.get("throughput")]
    if oks:
        avg = sum(r["throughput"] for r in oks) / len(oks)
        avg_proc = sum(r["procSec"] for r in oks) / len(oks)
        print("  >> 平均处理 %.2fs，平均吞吐 %d 行/s（%d 次成功）" % (avg_proc, avg, len(oks)))
    summary = {"mode": args.mode, "label": args.label, "runs": res,
               "avgThroughput": (sum(r["throughput"] for r in oks) / len(oks)) if oks else None}
    print("RESULT_JSON " + json.dumps(summary, ensure_ascii=False))

if __name__ == "__main__":
    main()
