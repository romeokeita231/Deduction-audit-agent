# 评估报告

2026-10-31 · commit `6f682a9` · `schemaVersion` 2 · 15 条语料

本次为 replay 模式，回放 cassette，不调用模型。语料与 oracle 冻结，时间由真时钟解析窗口。

`metrics.json` 钉住 corpus sha256 `891be561cf8cfaeae5368c6ca5d62916361e0a689659b7f3ad5db67b765906a3`、policy sha256 `3dde491d3d993fb2dbaa3a9d727e03864285fc8e223a25179ed614a5d89fc2d8`、commit sha 与 `schemaVersion`。

负向对照来自 manifest：
- `PRICE-SUPPORTS_RETAILER-01` — draft unavailable must surface as DRAFT_UNAVAILABLE and lower the yield

## 指标 1 · 判决一致率

micro exact-match **15/15**。四态 one-vs-rest 召回：

- `DEDUCTION_STANDS`: 3/3
- `DISPUTABLE`: 6/6
- `EVIDENCE_GAP`: 3/3
- `POLICY_GAP`: 3/3

三个 category 的 exact-match：

- `SHORTAGE`: 5/5
- `PRICE_VARIANCE`: 5/5
- `PROMOTION`: 5/5

## 指标 2 · 证据引用

只在 `claim.supportingEvidence` 上计算。分母为 oracle 判 `DISPUTABLE` 的 6 条。剔除行：无 claim 3 条、字段不对 3 条、无规则可裁 3 条。实际产出 claim 的 case 5 条，其中零引用 0 条。

- precision: 10/10
- recall: 10/10

## 指标 3 · 文书结构

结构合格率 **5/5**，分母只含实际产出的文书。产出率 **5/6**，分子是实际产出的文书数，分母是 oracle 判 `DISPUTABLE` 的条数。两个数永不相乘。起草失败的行把 `error` 写在 NDJSON 里，仍计入产出率的分母。

## 复现

```powershell
mvn -B test -Dtest=CiFixtureTest
python -m eval.metrics docs/eval/reports/2026-10-31-6f682a9/results.ndjson --report docs/eval/reports --commit 6f682a9 --mode replay --manifest src/test/resources/eval/ci-manifest.json
```
