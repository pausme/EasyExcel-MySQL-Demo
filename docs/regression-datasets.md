# 回归数据集治理

本文档用于统一说明本项目的回归测试样本如何生成、命名和清理。所有生成产物默认写入 `target/test-fixtures/regression`，不提交到 Git。

## 1. 统一生成入口

```bash
python3 scripts/gen_regression_fixtures.py
```

可选参数：

| 参数 | 默认值 | 说明 |
| --- | ---: | --- |
| `--out` | `target/test-fixtures/regression` | 输出目录 |
| `--rows` | `10` | 小样本 Excel 数据行数 |
| `--binary-size` | `6291456` | 分片上传二进制样本大小，默认 6MB |

性能压测大文件仍使用：

```bash
python3 scripts/gen_perf_import_file.py --rows 1000000 --out target/test-fixtures/perf_1m.xlsx
```

## 2. 样本清单

| 文件 | 用途 |
| --- | --- |
| `student-valid-small.xlsx` | 合法小 Excel，验证导入成功路径 |
| `student-empty-template.xlsx` | 只有表头，验证空模板导入 |
| `student-duplicate-no.xlsx` | 文件内重复学号，验证重复业务键拦截和错误明细 |
| `student-missing-required.xlsx` | 缺失学号/姓名，验证必填校验 |
| `student-long-fields.xlsx` | 姓名、班级、邮箱超长，验证长度校验 |
| `fake-excel.xlsx` | 非 zip/xlsx 文件伪装成 Excel，验证提交阶段魔数校验 |
| `multipart-sample.bin` | 分片上传样本，验证分片上传、断点查询和合并 |
| `perf_*.xlsx` | 性能压测大文件，按需生成，不作为常规回归必跑样本 |

## 3. 命名和清理规则

- 常规样本统一使用 `student-<scenario>.xlsx`。
- 二进制样本统一使用 `<module>-sample.bin`。
- 性能样本统一使用 `perf_<rows>.xlsx` 或 `perf_<rows>_<case>.xlsx`。
- 生成目录固定在 `target/test-fixtures` 或 `.tmp` 下，避免混入源码目录。
- 回归完成后可直接删除 `target/test-fixtures`。

## 4. 使用建议

1. 单元测试优先在内存中构造小样本，避免依赖磁盘 fixture。
2. 接口扁平化和手工联调使用本脚本生成的稳定样本。
3. 性能压测样本只在需要时生成，避免把大文件提交到仓库或长期占用磁盘。
4. 新增导入校验规则时，同步扩展本脚本和本文档的样本清单。

