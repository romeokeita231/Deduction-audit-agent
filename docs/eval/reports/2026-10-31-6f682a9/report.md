# CI fixture 评估报告

2026-10-31 · commit `6f682a9` · `schemaVersion` 2 · 15 条语料（三类 × 五种情形各 1 条）

这份报告来自回放 cassette，不调模型，所以它衡量的是管线，不是模型的判断力（[ADR 0007](../../../adr/0007-ci-runs-pipeline-not-model.md)）。oracle 是代码，标注在语料里，两者都冻结。

## 指标 1 · 判决一致率

15/15 与 oracle 一致；四态召回各格均为满分。这一格全部命中是预期内的——回放 cassette 本身就是按 oracle 冻结的判决录下来的。

## 指标 2 · 证据引用

只在 `claim.supportingEvidence` 上计算，引用必须落在封闭集内。15 条里 5 条产出 claim，精确率与召回的分母都是这 5 条实际引用的 10 个 ID。

## 指标 3 · 文书结构

结构合格率 5/5，分母只含实际产出的文书；产出率 **5/6**。

分母 6 是 oracle 判 `DISPUTABLE` 的条数，分子 5 是真正产出了文书的条数。差的这 1 条不是管线丢失，而是刻意的负向对照：**`PRICE-SUPPORTS_RETAILER-01`** 在 `ci-manifest.json` 里带着 `why`、在 drafts 通道里没有条目，它存在的唯一目的就是让"起草失败"必须显式可见——该行 `status` 停在 `AWAITING_ACTION`、`error` 写成 `DRAFT_UNAVAILABLE: draft missing`、`narrative` 为 `null`，并被计进上述产出率的分母。绿态的 fixture 原本覆盖不到这条路径，这一条把它补上了。

## 复现

```powershell
$env:DOCKER_HOST="tcp://127.0.0.1:2375"
mvn -B test -Dtest=CiFixtureTest
```

`metrics.json` 钉住 corpus sha256、policy sha256、commit sha 与 `schemaVersion`；`negativeControls` 与 manifest 双向同步，由 `theBaselineMetricsMirrorTheManifestsNegativeControls` 守着。
