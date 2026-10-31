# 评估报告

2026-10-31 · commit `544f8ed` · `schemaVersion` 2 · 105 条语料

本次为 live 模式，真打模型，不回放 cassette。语料与 oracle 冻结，时间由真时钟解析窗口。

`metrics.json` 钉住 corpus sha256 `891be561cf8cfaeae5368c6ca5d62916361e0a689659b7f3ad5db67b765906a3`、policy sha256 `3dde491d3d993fb2dbaa3a9d727e03864285fc8e223a25179ed614a5d89fc2d8`、commit sha 与 `schemaVersion`。

本次为 live 运行，产出率差额来自模型实际失败，不含人为对照。

## 指标 1 · 判决一致率

micro exact-match **63/105**。四态 one-vs-rest 召回：

- `DEDUCTION_STANDS`: 20/30
- `DISPUTABLE`: 22/33
- `EVIDENCE_GAP`: 14/21
- `POLICY_GAP`: 7/21

三个 category 的 exact-match：

- `SHORTAGE`: 28/35
- `PRICE_VARIANCE`: 28/35
- `PROMOTION`: 7/35

## 指标 2 · 证据引用

只在 `claim.supportingEvidence` 上计算。分母为 oracle 判 `DISPUTABLE` 的 33 条。剔除行：无 claim 30 条、字段不对 21 条、无规则可裁 21 条。实际产出 claim 的 case 14 条，其中零引用 0 条。

- precision: 28/28
- recall: 28/28

## 指标 3 · 文书结构

结构合格率 **11/21**，分母只含实际产出的文书。产出率 **21/33**，分子是实际产出的文书数，分母是 oracle 判 `DISPUTABLE` 的条数。两个数永不相乘。起草失败的行把 `error` 写在 NDJSON 里，仍计入产出率的分母。

## 复现

```powershell
mvn -P live spring-boot:run
python -m eval.metrics target/eval/results.ndjson --report docs/eval/reports --commit 544f8ed --mode live
```
