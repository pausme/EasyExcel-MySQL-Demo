# -*- coding: utf-8 -*-
"""
生成百万级行的学生导入 Excel（流式写入，低内存）。
用于导入性能压测。学号唯一，确保走 INSERT 路径。

用法：
    python3 scripts/gen_perf_import_file.py --rows 1000000 --out /tmp/perf_1m.xlsx
"""
import argparse, time
from openpyxl import Workbook

HEADER = ["学号", "姓名", "年龄", "性别", "班级", "邮箱", "生日"]

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rows", type=int, default=1000000)
    ap.add_argument("--out", default="/tmp/perf_1m.xlsx")
    ap.add_argument("--prefix", default="PERF")
    args = ap.parse_args()

    t0 = time.time()
    wb = Workbook(write_only=True)
    ws = wb.create_sheet("学生数据")
    ws.append(HEADER)
    n = args.rows
    z = len(str(n))
    for i in range(1, n + 1):
        sno = "%s%0*d" % (args.prefix, z, i)
        ws.append([sno, "学生%d" % i, 18 + (i % 40), "男" if i % 2 else "女",
                   "班级%d" % (i % 50), "perf%d@example.com" % i, "2000-01-01"])
    wb.save(args.out)
    import os
    dt = time.time() - t0
    print("生成完成: %s" % args.out)
    print("行数: %d  文件大小: %.1f MB  耗时: %.1fs" % (n, os.path.getsize(args.out) / 1048576.0, dt))

if __name__ == "__main__":
    main()
