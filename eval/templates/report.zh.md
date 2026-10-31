# 评估报告

$date · commit `$commit` · `schemaVersion` $schema_version · $case_count 条语料$limited_note

本次为 $mode 模式，$mode_note语料与 oracle 冻结，时间由真时钟解析窗口。

`metrics.json` 钉住 corpus sha256 `$corpus_sha256`、policy sha256 `$policy_sha256`、commit sha 与 `schemaVersion`。

$controls_note

## 指标 1 · 判决一致率

micro exact-match **$micro_exact_match**。四态 one-vs-rest 召回：

$state_recall

三个 category 的 exact-match：

$category_exact_match

## 指标 2 · 证据引用

只在 `claim.supportingEvidence` 上计算。分母为 oracle 判 `DISPUTABLE` 的 $disputable 条。剔除行：无 claim $no_claim 条、字段不对 $wrong_field 条、无规则可裁 $unarbitrated 条。实际产出 claim 的 case $claimed 条，其中零引用 $zero_reference 条。

- precision: $precision
- recall: $recall

## 指标 3 · 文书结构

结构合格率 **$structural_pass**，分母只含实际产出的文书。产出率 **$production_rate**，分子是实际产出的文书数，分母是 oracle 判 `DISPUTABLE` 的条数。两个数永不相乘。起草失败的行把 `error` 写在 NDJSON 里，仍计入产出率的分母。

## 复现

```powershell
$pipeline_command
$archive_command
```
