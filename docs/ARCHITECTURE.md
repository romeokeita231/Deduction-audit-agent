# 架构

Spring Boot 4.1 单进程承担全部运行时；Python 只做离线的合成数据生成与评估，不参与运行时、与主进程无 IPC。为什么不拆成两个服务，见 [ADR 0002](./adr/0002-java-runtime-python-only-for-evaluation.md)。

```
Browser ── Thymeleaf 两页（队列页 / 详情页）
   │ HTTP
Spring Boot 4.1（单进程，JDK 25）
   ├─ Web 层        队列、详情、触发、提交争议、录入结果
   ├─ 域层          Deduction 聚合 · 状态机 · 窗口计算 · 路由（查表，无模型）
   ├─ Agent 层      Validator ×3（tool calling 交叉核验）
   │                Drafter（结构化输出 + 证据引用）
   ├─ @Tool ×7      只读，按类别裁剪（见下）
   └─ Trace         每步落 Mongo：入参、出参、耗时、token
        │
        ├── JDBC + pgvector ── PostgreSQL：扣款 / 争议 / 状态 / 促销协议向量
        └── Mongo driver ───── MongoDB：原始 EDI 812 JSON / Agent 轨迹（append-only）

离线，不参与运行时、无 IPC：
   Python 3.12 ── 合成数据生成 + 评估 harness ── 读 PG 结果表
```

**它不是 RAG 问答**：RAG 的输入是一段文本、输出是一段文本；这里的输入是一笔扣款，输出是一个可以拿去提交的判定 + 证据集 + 文书，中间必须调用工具去交叉验证三份互相矛盾的数据源。

## 工具集

| 工具 | 返回什么 | 缺货 | 价格 | 促销 |
|---|---|:--:|:--:|:--:|
| `getAsn` | EDI 856：我们申报发货的量与时间 | ✓ | | |
| `getBol` | 提单：承运人记录的实发量 | ✓ | | |
| `getPod` | 签收单：收货方记录的到货量 | ✓ | | |
| `getPriceMaster` | 某 SKU 在某日生效的单价 | | ✓ | |
| `getInvoiceLine` | 我们实际开票的单价与数量 | | ✓ | |
| `searchPromotionAgreements` | 向量检索促销条款，带 `docId` | | | ✓ |
| `getPromotionSettlement` | 协议该结多少 vs 结算记录实结多少 | | | ✓ |

四条规则：

- **一笔扣款只跑一个 Validator。** 路由由映射表决定，映射不到就进 `PARKED`。**路由路径里没有模型调用**，也不打算加——分类是可运维的数据，不是一次推理。
- **工具按类裁剪。** 缺货类的模型看不到 Price Master。工具越少，选错概率越低，同时也是最小权限。
- **每个工具的返回值必须带稳定的 `evidence_id`**（见 [ADR 0005](./adr/0005-evidence-ids-from-natural-keys.md)）。它要同时贯穿三处：工具返回值、生成器写给 oracle 的标注、文书里的引用。事后补不出来。
- **EDI 812 不是工具，它在 prompt 里。** 它是这笔扣款的*主张*，不是事实——BOL 和 POD 打架时，812 仲裁不了任何一方。让它当"终审来源"是概念错误；仲裁不了的时候，正确答案是 `INCONCLUSIVE`。

## 端点

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/deductions?status=&category=` | 队列页数据 |
| GET | `/deductions/{id}` | 详情：证据、Verdict、轨迹、文书 |
| POST | `/deductions/{id}/evaluate` | 触发 Validator（接受 `NEW` 与 `PARKED`） |
| POST | `/deductions/{id}/dispute` | 提交争议（守卫：`DISPUTABLE`） |
| POST | `/deductions/{id}/close` | 接受 / 放弃 / 转人工（body 带 outcome） |
| POST | `/deductions/{id}/result` | 录入 `WON` / `LOST` |

对 `PARKED` 的一次 `/evaluate` 覆盖 `PARKED → NEW → EVALUATING` 两条转移，历史里记两行。`UNMAPPED_CODE` 且映射表仍未补齐时返回 409——不补映射就重跑，只会把同一条扣款再停一次；`POLICY_GAP` 同理，重跑前必须先改政策文本，靠 `policyVersion` 校验。

## 争议文书

两段式，先结构后文字，**两段的作者不同**（[ADR 0008](./adr/0008-dispute-document-projection-and-narrative.md)）：

- `claim`：`amount`、`currency`、`ask`（只带 `type`，不带金额）、`references[]`（标的物）、`supportingEvidence[]`（支持结论的证据）、`basis[]`（引用的协议条款 ID）。**全部确定性投影自 Verdict 与 Deduction 行，模型不产出任何字段。**
- `narrative`：模型写的一段英文散文，全系统唯一由模型生成、且面向客户的文本。
- `digest`：按 `claim` 三个 ID 集合的并集逐项回查证据得到的事实快照——覆盖集合与并集相等，不多不少——与文书一起冻结，带 `evidenceVersion`。

硬校验五条，全部确定性：两个数组都是**封闭集**（`references` 额外允许这笔扣款自己的 812）；`basis ⊆ supportingEvidence ∪ references`；`amount` / `currency` 与 Deduction 行逐字相等；正文出现的每个 ID 都落在三个集合内、且至少一个；正文无 markdown 标记。越界判失败，不进人工评审。

生成不在事务内：守卫（只读）→ digest → 模型往返 → 单事务落库与转移。模型不可用是 503，投影矛盾是 500——重试永远不会成功的错误必须让调用方看见。

**为什么拆成两个数组**：812 是被争的对象，不是支持性证据。混在一个数组里，一份如实写了"零售商依据 812 第 3 行主张缺货"的文书会被精确率扣分——**指标不该惩罚诚实**。

## 时间预算

30 秒是 **p95 目标**，45 秒是硬截止；两者都是这笔扣款的总预算，不是每次尝试的预算。

| 项 | 上限 |
|---|---|
| 单次工具调用 | 3s |
| 单次模型往返 | 12s |
| 一次评估的工具调用总数 | 6（Spring AI 默认 40/150，按构造收紧） |
| `EVALUATING` 整体 | 45s 硬截止（p95 目标 30s） |
| 重试 | 至多 1 次，且只在剩余预算 > 12s 时发生 |

超时 → `PARKED(EVAL_FAILED)`，已拿到的部分轨迹照常写入。重试只针对基础设施失败（工具异常、429、超时），**不针对"裁决结果不满意"**——后者是把评估变成刷榜。

## 相关文档

- [状态机](./STATE-MACHINE.md)——10 个状态、13 条转移、7 条不变量
- [术语表](../GLOSSARY.md)
- ADR：[0001 扣款是事件](./adr/0001-deduction-is-an-event-not-a-state.md) · [0002 单进程](./adr/0002-java-runtime-python-only-for-evaluation.md) · [0003 一台状态机](./adr/0003-one-state-machine-on-deduction.md) · [0004 规则进上下文、答案不进](./adr/0004-rules-in-context-answers-never.md) · [0005 证据 ID](./adr/0005-evidence-ids-from-natural-keys.md) · [0006 轨迹是旁路](./adr/0006-trace-is-a-sidecar.md) · [0007 CI 不跑模型](./adr/0007-ci-runs-pipeline-not-model.md) · [0008 结构与正文的作者](./adr/0008-dispute-document-projection-and-narrative.md)
