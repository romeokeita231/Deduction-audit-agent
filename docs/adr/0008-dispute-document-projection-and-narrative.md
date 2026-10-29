# 文书结构由判决投影，正文由模型写

Status: accepted

争议文书分两段，两段的作者不同。`claim`（金额、诉求、引用、证据、条款依据）由 Verdict 与 Deduction 行确定性投影，模型不产出其中任何字段；`narrative`（正文）由模型写，是**全系统唯一一处模型输出进入客户可见文本**的地方。另有一份 `digest`：按 `claim` 引用的 ID 回查证据得到的事实快照，与文书一起冻结，带证据版本号。

原来的架构文档写的是"`narrative` 由 `claim` 渲染"，可以被读成模板。两种读法都有真实的代价，所以这条要显式定下来。

## 为什么不全部交模型

ADR 0005 把证据 ID 的封闭集守在了 Verdict 上。让模型再产出一份 `claim`，等于在判决之后又开了一次庭：结论、金额、引用都有了第二次被改写的机会，而客户可见的文书正是编造证据 ID 代价最高的地方。

## 为什么不全部确定性

模板拼出来的 `POD:SHIP-0442 = 480` 不是 AR 会发给零售商的英文。结构可以投影，语篇不行。

## 结构性约束

- **金额只有一个来源**：`claim.amount` / `claim.currency` 投影自 Deduction 行，`ask` 只留 `{"type": "REVERSE_DEDUCTION"}`。两份金额必然出现"改一个忘一个"。
- **`basis` 是子集而非等同**：`basis ⊆ supportingEvidence ∪ references`，不是 `⊆ supportingEvidence`。前者仍是封闭集、仍可确定性校验，后者会把文书正确性寄生在某条 prompt 指令的引用习惯上——模型少写一个 clause id，整份文书就失败。
- **正文要过一遍 ID 扫描**：出现的每个证据 ID 必须落在 `claim` 的三个集合内，且至少一个。否则 ADR 0005 在第二个地方没守。
- **生成不进事务**：守卫（只读）→ digest → 模型往返（12s）→ 单事务落库与转移。把模型包进 `@Transactional` 会按住 PG 连接与行锁 12 秒，还会污染 `noRollbackFor = WindowExpiredException` 的事务语义。
- **失败按责任方分档**：模型不可用 = 503 `DRAFT_FAILED`，可重试；投影矛盾、ID 回查不到、正文越界 = 500 `DRAFT_INCONSISTENT`，重试永远不会成功。

## Consequences

- 指标 3 从"文书可用率"降级为确定性的"文书结构完整率"。它不再测模型——`claim` 是投影，这个数只反映管线。judge 若要用，输入口径固定为 `claim` + `narrative` + `digest` 三元组，不当主指标。
- `digest` 与 `evidence_version` 成为回放与复算的前提：证据种子 append-only，改种子必须升版本，否则历史文书对不上。
- 出现第二条模型路径，回放 fixture 需要第二个通道（`drafts`），`prompt_version` 也必须单独哈希、单独做护栏测试。第一条路径踩过的坑会在第二条路径原样复发。
- 文书一旦写入不可改写；生成失败不落行、可重试。这两句必须分开表述——否则一次模型抖动就把这笔扣款钉在 `AWAITING_ACTION` 直到窗口过期。
