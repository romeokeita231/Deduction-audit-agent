# 扣款状态机

一笔 Deduction 一条状态机。Dispute 没有独立状态机，理由见 [ADR 0003](./adr/0003-one-state-machine-on-deduction.md)。

## 状态

| 状态 | 含义 | 终态 | 进 Win Rate 分母 |
|---|---|:--:|:--:|
| `NEW` | 已入库，等待分类与触发 | | |
| `PARKED` | 停靠等人工：原因码未映射 / 评估连续失败 / 证据不足 | | |
| `EVALUATING` | Validator 正在跑 | | |
| `AWAITING_ACTION` | Verdict 已出，等 AR 决定争不争 | | |
| `DISPUTED` | 文书已生成、由 AR 在系统外提交，等零售商议结果 | | |
| `ACCEPTED` | 接受扣款（认可零售商） | ✓ | |
| `WRITTEN_OFF` | 放弃争议（不认可，但成本不划算） | ✓ | |
| `EXPIRED` | 争议窗口已过 | ✓ | |
| `WON` | 争议成功，拿到 Credit Memo | ✓ | ✓ |
| `LOST` | 争议失败 | ✓ | ✓ |

`ACCEPTED` 与 `WRITTEN_OFF` 都是"没拿回钱"，但业务含义相反：前者认账，后者认亏不认理。**合并成一个状态会丢掉这个区别**，而这个区别决定了回收率报表能不能解释钱是怎么丢的。

`DISPUTED` 不进分母：分母的定义是"已出结果"，不是"提交过"。未决争议计入分母会让赢率随处理速度波动。

`DISPUTED` 不表示系统完成了对外交互：本系统没有 EDI 出口，文书导出后由 AR 在系统外发送，`WON` / `LOST` 也是人工回填。

## 转移

| 从 | 到 | 触发 | 守卫 |
|---|---|---|---|
| `NEW` | `PARKED` | 分类 | Reason Code 无映射 |
| `NEW` | `EXPIRED` | 入库检查 | `now > windowEndsAt` |
| `NEW` | `EVALUATING` | 人工 / 批量触发 | 已映射，且未被占用 |
| `PARKED` | `NEW` | 人工补映射或重试 | |
| `PARKED` | `EXPIRED` | 定时扫描 | `now > windowEndsAt` |
| `PARKED` | `WRITTEN_OFF` | 人工放弃 | 补证不划算时直接放弃，不必重跑一遍评估 |
| `EVALUATING` | `AWAITING_ACTION` | Validator 返回 | Verdict 已落库 |
| `EVALUATING` | `NEW` | 基础设施失败（工具异常 / 429 / 超时） | 已重试 0 次（至多 1 次），且本次剩余预算 > 12s（≥ 一次完整模型往返） |
| `EVALUATING` | `PARKED` | 同上 | 已重试 1 次仍失败，或超出 45s 硬截止 |
| `AWAITING_ACTION` | `DISPUTED` | AR 确认提交（文书已导出，系统不代发） | Verdict 为 `DISPUTABLE` |
| `AWAITING_ACTION` | `ACCEPTED` | AR 接受扣款 | Verdict 为 `DEDUCTION_STANDS` |
| `AWAITING_ACTION` | `WRITTEN_OFF` | AR 放弃 | 任意 Verdict |
| `AWAITING_ACTION` | `PARKED` | 服务端自动停靠 | Verdict 为 `INCONCLUSIVE` 时自动停靠。**证据不足不能直接提交争议** |
| `AWAITING_ACTION` | `EXPIRED` | 定时扫描 | `now > windowEndsAt` |
| `DISPUTED` | `WON` / `LOST` | 录入零售商议结果 | |

## `PARKED` 的四种理由

`PARKED` 是统一的"人来看一眼"队列，但**理由必须能区分**：队列页只显示"停靠"的话，看的人不知道该补映射还是补单据。

| `parkedReason` | 触发 | 人该做什么 |
|---|---|---|
| `UNMAPPED_CODE` | Reason Code 不在映射表 | 补映射，回 `NEW` |
| `EVAL_FAILED` | 重试后仍失败（共 2 次尝试） | 看 Mongo 里的 trace，修完回 `NEW` |
| `EVIDENCE_GAP` | Verdict 为 `INCONCLUSIVE`，且 `missingEvidence` 非空 | 补证据（找承运人要 POD），回 `NEW` 重跑；补不到就 `WRITTEN_OFF` |
| `POLICY_GAP` | Verdict 为 `INCONCLUSIVE`，且 `missingEvidence` 为空 | 规则没覆盖这种冲突。补证据没用，要人工裁定或补政策，然后回 `NEW` |

`EVIDENCE_GAP` 与 `POLICY_GAP` 都归 `INCONCLUSIVE`，但补救动作完全不同——一个去要单据，一个去改规则。**不区分的话，"证据不足"这四个字会让人白跑一趟。**

这是**字段不是状态**：四种理由的生命周期完全一样，拆成四个状态只会让转移表翻四倍。

## Verdict（不是状态）

Validator 的输出，独立于生命周期的三个取值：

- `DEDUCTION_STANDS` —— 扣款成立
- `DISPUTABLE` —— 可争议
- `INCONCLUSIVE` —— 证据不足

**不要用 `valid` / `invalid`。** 它没说清"对谁有效"：对供应商有效的结论（这笔能争）和零售商的扣款有效（这笔站得住）是相反的。这个词一旦进了标注表，准确率就没有意义了。

字段：

| 字段 | 说明 |
|---|---|
| `verdict` | `DEDUCTION_STANDS` / `DISPUTABLE` / `INCONCLUSIVE` |
| `missingEvidence` | 证据种类枚举数组，只对 `INCONCLUSIVE` 有值；取值 `POD` / `BOL` / `ASN` / `INVOICE_LINE` / `PROMOTION_SETTLEMENT` |
| `references` | 标的物：这笔 Deduction、它的 812 记录。进封闭集校验，**不进指标** |
| `supportingEvidence` | 支持结论的证据 ID（BOL / POD / ASN / Invoice Line / Promotion Settlement）。**指标 2 只算这个** |
| `reasoning` | 判定理由，自由文本，**不进任何指标** |
| `promptVersion` / `policyVersion` / `mappingVersion` | 复现与重跑守卫用。`policyVersion` = 政策文本哈希，`mappingVersion` = 原因码映射表版本 |
| `model` / `evaluatedAt` | 复现用，只写不读 |

**引用为什么拆成两个数组**：812 是被争的对象，不是支持性证据。混进一个数组里，一份如实写了"零售商依据 812 第 3 行主张缺货"的文书会被精确率扣分——越诚实越吃亏。拆开之后：引用标的不扣分，引用证据才计分，编造编号两侧都会被抓。

**没有 `confidence` 字段。** 模型自报的置信度是噪声；一旦有了这个字段，就会有人问"0.7 以上才自动提交吗"，而那个阈值辩护不了。三态本身就是不确定性的表达。

**`missingEvidence` 为空不等于"证据齐全"**：它也出现在"规则没覆盖这种冲突"的情况里。按 `missingEvidence` 是否为空，`PARKED` 分成 `EVIDENCE_GAP` 和 `POLICY_GAP` 两种理由。

## 不变量

1. 终态拒绝任何转移——ADR 0001 的"关闭后不可改写"由此自动成立。
2. `DISPUTED` 之后窗口不再计时：AR 已确认提交（文书已导出），逾期风险转移到零售商侧。
3. `EXPIRED` 只能从 `NEW` / `PARKED` / `AWAITING_ACTION` 进入，且必须满足 `now > windowEndsAt`。
4. 同一 Deduction 同时最多一个 `EVALUATING`（Postgres advisory lock）。
5. `DISPUTED` 必须存在对应的 Dispute 记录，且 `openedAt` 非空；`WON` / `LOST` 必须 `closedAt` 与 `result` 非空。
6. `windowEndsAt` 在入库时一次性算定（扣款日 + 该零售商窗口天数），此后只读。**它是零售商本地时区的 `LocalDate`，不是 `Instant`**：到期日当天仍可提交，只有 `now(零售商店区).toLocalDate() > windowEndsAt` 才算过期。时钟一律按店区算，不按服务器时区算——否则跨时区会白丢或白赚一天。
7. `INCONCLUSIVE` 永不允许进入 `DISPUTED`。没有证据的争议会同时拉低引用精确率和真实胜率，而且它把"Agent 敢说不知道"这件事变成了空话。

## 定时与惰性

`EXPIRED` 有两条到达路径，两条都要有：

- **定时**：每天一次扫描，把过期的 `NEW` / `PARKED` / `AWAITING_ACTION` 翻成 `EXPIRED`。
- **惰性**：任何一次转移尝试都先校验 `now > windowEndsAt`，即使调度器没跑到也不允许把过期扣款提交成争议。

只做定时会有一个藏不住的漏洞：调度器挂了或服务停了一晚，过期扣款还能被提交。只做惰性则队列页永远显示"还剩 0 天"而不变灰。两条都做，代码量约 20 行。

## 队列页的排序

按 `daysRemaining` 升序，**`PARKED` 必须参与排序**。停靠不是免死金牌：窗口照走，逾期照损。如果 `PARKED` 被排除在排序之外，它就会变成一个静悄悄烧钱的洞，而这恰恰是最值得在录屏里演示的一幕。
