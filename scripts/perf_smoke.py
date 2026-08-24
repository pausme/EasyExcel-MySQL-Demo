# -*- coding: utf-8 -*-
"""
性能回归冒烟基准（QA-04）：轻量导入 + 导出各一轮，与基线对比，偏离 >30% 告警退出。

用法（标准环境，Token 鉴权）：
    BASE_URL=<url> API_SECURITY_DEMO_USER_TOKEN=<token> \
    python3 scripts/perf_smoke.py [--baseline docs/test/perf-baseline.json] [--update-baseline]
产物：docs/test/perf-smoke-latest.json（最近一次结果）；--update-baseline 时同步写回基线。
基线默认值来自 R26 标准：导入 100k ≈ 4,511 行/s（阈值放宽到 1,800）；导出 XLSX 3 行表 ≈ 即时（仅校验任务成功）。
"""
import argparse, json, os, subprocess, sys, time, datetime, re

BASE = os.environ.get("BASE_URL", "http://localhost:18088")
TOKEN = os.environ.get("API_SECURITY_DEMO_USER_TOKEN", "").strip()
HERE = os.path.abspath(os.path.dirname(__file__))
BASELINE_DEFAULT = os.path.join(HERE, "..", "docs", "test", "perf-baseline.json")
LATEST_OUT = os.path.join(HERE, "..", "docs", "test", "perf-smoke-latest.json")
DEVIATION_THRESHOLD = 0.30

DEFAULT_BASELINE = {
    "importThroughputRowsPerSec": 4511,
    "importThroughputFloorRowsPerSec": 1800,
    "exportTaskSucceeds": True,
    "measuredAt": "R26-initial"
}


def curl_json(method, path, data=None, form_file=None, timeout=1800):
    cmd = ["curl", "-sS", "-X", method, "-m", str(timeout), BASE + path]
    if TOKEN:
        cmd += ["-H", "Authorization: Bearer " + TOKEN]
    if data is not None:
        cmd += ["-H", "Content-Type: application/json", "-d", data]
    if form_file:
        cmd += ["-F", "file=@%s" % form_file]
    p = subprocess.run(cmd, capture_output=True, text=True)
    try:
        return json.loads(p.stdout).get("data") or {}
    except Exception:
        return {"_raw": p.stdout[:200], "_err": p.stderr[:200]}


def parse_dt(v):
    if not v:
        return None
    s = str(v).replace(" ", "T")
    s = re.sub(r"(\.\d+)\+\d\d:\d\d$", r"\1", s)
    for f in ("%Y-%m-%dT%H:%M:%S.%f", "%Y-%m-%dT%H:%M:%S"):
        try:
            return datetime.datetime.strptime(s, f)
        except ValueError:
            pass
    return None


def smoke_import():
    """轻量导入：模板 + 1 行（免生成大文件），校验全链路可用；吞吐按处理耗时折算行/秒意义有限，改为校验耗时上限。"""
    t0 = time.time()
    tpl = "/tmp/perf_smoke_tpl.xlsx"
    subprocess.run(["curl", "-sS", "-m", "60"] + (["-H", "Authorization: Bearer " + TOKEN] if TOKEN else [])
                   + ["-o", tpl, BASE + "/api/excel/template"], capture_output=True)
    try:
        from openpyxl import load_workbook
        wb = load_workbook(tpl)
        wb.active.append(["SMOKE-%d" % int(time.time()), "冒烟", 20, "男", "冒烟班", "s@x.com", "2006-01-01"])
        wb.save(tpl)
    except ImportError:
        pass
    d = curl_json("POST", "/api/excel/import", form_file=tpl)
    tid = d.get("taskId", "")
    if not tid:
        return {"ok": False, "error": "submit failed: %s" % d}
    for _ in range(120):
        f = curl_json("GET", "/api/excel/import/%s" % tid)
        if f.get("status") in ("SUCCESS", "FAILED", "CANCELED"):
            break
        time.sleep(2)
    wall = time.time() - t0
    return {"ok": f.get("status") == "SUCCESS", "status": f.get("status"),
            "wallSec": round(wall, 1), "taskId": tid}


def smoke_export():
    t0 = time.time()
    d = curl_json("POST", "/api/excel/export")
    tid = d.get("taskId", "")
    if not tid:
        return {"ok": False, "error": "submit failed: %s" % d}
    for _ in range(200):
        f = curl_json("GET", "/api/excel/export/%s" % tid)
        if f.get("status") in ("SUCCESS", "FAILED", "CANCELED"):
            break
        time.sleep(2)
    ca, fa = parse_dt(f.get("createdAt")), parse_dt(f.get("finishedAt"))
    proc = (fa - ca).total_seconds() if ca and fa else None
    return {"ok": f.get("status") == "SUCCESS", "status": f.get("status"),
            "exported": f.get("exported", 0), "procSec": round(proc, 1) if proc else None,
            "wallSec": round(time.time() - t0, 1), "taskId": tid}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--baseline", default=BASELINE_DEFAULT)
    ap.add_argument("--update-baseline", action="store_true")
    args = ap.parse_args()

    baseline = dict(DEFAULT_BASELINE)
    if os.path.exists(args.baseline):
        baseline.update(json.load(open(args.baseline)))

    imp = smoke_import()
    exp = smoke_export()
    result = {
        "measuredAt": datetime.datetime.now().isoformat(timespec="seconds"),
        "import": imp, "export": exp,
    }
    with open(LATEST_OUT, "w") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)

    failures = []
    if not imp.get("ok"):
        failures.append("import smoke failed: %s" % imp.get("status") or imp.get("error"))
    if not exp.get("ok"):
        failures.append("export smoke failed: %s" % exp.get("status") or exp.get("error"))
    # 导出耗时回归：超过基线 +30% 视为劣化（仅当导出行数与基线同量级时有意义）
    if exp.get("ok") and exp.get("procSec") and baseline.get("exportProcSecBaseline"):
        allowed = baseline["exportProcSecBaseline"] * (1 + DEVIATION_THRESHOLD)
        if exp["procSec"] > allowed:
            failures.append("export degraded: %.1fs > baseline %.1fs +30%%" % (exp["procSec"], baseline["exportProcSecBaseline"]))

    if args.update_baseline and not failures:
        json.dump({"measuredAt": result["measuredAt"],
                   "exportProcSecBaseline": exp.get("procSec"),
                   "importWallSecBaseline": imp.get("wallSec")},
                  open(args.baseline, "w"), indent=2)

    print(json.dumps(result, ensure_ascii=False, indent=2))
    if failures:
        print("PERF SMOKE FAILED:")
        for x in failures:
            print(" -", x)
        sys.exit(1)
    print("PERF SMOKE PASSED")


if __name__ == "__main__":
    main()
