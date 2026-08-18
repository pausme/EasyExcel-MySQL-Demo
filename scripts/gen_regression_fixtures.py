#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Generate deterministic regression fixtures for import/export and file-center tests.

Outputs are written under target/test-fixtures by default and should not be committed.
"""
import argparse
import hashlib
import os
from pathlib import Path

from openpyxl import Workbook


HEADER = ["学号", "姓名", "年龄", "性别", "班级", "邮箱", "生日"]


def create_workbook(path, rows):
    workbook = Workbook()
    sheet = workbook.active
    sheet.title = "学生数据"
    sheet.append(HEADER)
    for row in rows:
        sheet.append(row)
    workbook.save(path)


def base_rows(prefix, count):
    rows = []
    for index in range(1, count + 1):
        rows.append([
            "%s%04d" % (prefix, index),
            "测试学生%d" % index,
            18 + index % 30,
            "男" if index % 2 else "女",
            "回归%d班" % (index % 5),
            "%s%04d@example.com" % (prefix.lower(), index),
            "2000-01-01",
        ])
    return rows


def write_binary(path, size):
    digest = hashlib.sha256(str(path).encode("utf-8")).digest()
    with open(path, "wb") as output:
        remaining = size
        while remaining > 0:
            chunk = digest[:min(len(digest), remaining)]
            output.write(chunk)
            remaining -= len(chunk)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default="target/test-fixtures/regression")
    parser.add_argument("--rows", type=int, default=10)
    parser.add_argument("--binary-size", type=int, default=6 * 1024 * 1024)
    args = parser.parse_args()

    output_dir = Path(args.out)
    output_dir.mkdir(parents=True, exist_ok=True)

    valid_rows = base_rows("REG", args.rows)
    create_workbook(output_dir / "student-valid-small.xlsx", valid_rows)
    create_workbook(output_dir / "student-empty-template.xlsx", [])

    duplicate_rows = base_rows("DUP", max(2, args.rows))
    duplicate_rows[1][0] = duplicate_rows[0][0]
    create_workbook(output_dir / "student-duplicate-no.xlsx", duplicate_rows)

    missing_required_rows = base_rows("MISS", max(2, args.rows))
    missing_required_rows[0][0] = ""
    missing_required_rows[1][1] = ""
    create_workbook(output_dir / "student-missing-required.xlsx", missing_required_rows)

    long_field_rows = base_rows("LONG", max(1, args.rows))
    long_field_rows[0][1] = "超长姓名" + ("A" * 80)
    long_field_rows[0][4] = "超长班级" + ("B" * 80)
    long_field_rows[0][5] = "mail-" + ("c" * 140) + "@example.com"
    create_workbook(output_dir / "student-long-fields.xlsx", long_field_rows)

    fake_excel_path = output_dir / "fake-excel.xlsx"
    fake_excel_path.write_bytes(b"this is not a zip based xlsx file")

    write_binary(output_dir / "multipart-sample.bin", args.binary_size)

    print("fixtures generated: %s" % output_dir.resolve())
    for path in sorted(output_dir.iterdir()):
        print("%s\t%d bytes" % (path.name, path.stat().st_size))


if __name__ == "__main__":
    main()
