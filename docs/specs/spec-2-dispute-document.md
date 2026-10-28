# Spec 2 — M4 争议文书

Status: approved（grill 已闭环，尚未实现）

## 1. 目标与非目标

**目标**：一笔 `DISPUTED` 扣款有且只有一份不可改写的英文文书，由三部分组成——结构 `claim`、散文 `narrative`、冻结的 `digest`；轨迹里多一条 `draft` 轮次；详情页能看到这份文书。

**非目标**：

- 不新增端点。`POST /deductions/{id}/dispute` 的响应加 `document`，`GET /deductions/{id}` 复用同一形状。
- 不支持部分争议（按比例争）。`ask.type` 是单值枚举，留位不留实现。
- 文书写入后不可改写、不可重新生成。**生成失败不落行、可重试**——这两句是两件事。
- `POST /close`、`POST /result` 的 JSON 一个字节不变，也不为对称去补 `document`。
- 不产出邮件主题、不产出 markdown、不进人工评审队列。
- 不做文书可用率自动闸门（见 §9）。

## 2. 契约

### 2.1 claim

```json
{
  "deductionId": "8f14e45f-ceea-467f-a10b-1f3c2d3e4f50",
  "attemptNo": 1,
  "category": "SHORTAGE",
  "amount": "1240.00",
  "currency": "USD",
  "ask": { "type": "REVERSE_DEDUCTION" },
  "references": ["EDI812:8f14e45f-ceea-467f-a10b-1f3c2d3e4f50"],
  "supportingEvidence": ["POD:SHIP-0442", "BOL:SHIP-0442"],
  "basis": []
}
```

`claim` 全部由 Verdict 与 Deduction 行确定性投影，**模型不产出其中任何字段**：

| 字段 | 来源 |
|---|---|
| `deductionId` / `attemptNo` | 写事务内重新读取的守卫结果 |
| `category` | `deduction.category` |
| `amount` / `currency` | `deduction.amount` / `deduction.currency`，`amount` 为定标小数字符串 |
| `ask` | 常量 `{"type": "REVERSE_DEDUCTION"}`，**不含金额** |
| `references` | `[EDI812:<deductionId>] ∪ verdict.references`，812 固定首位，去重 |
| `supportingEvidence` | `verdict.supportingEvidence`，原顺序 |
| `basis` | `supportingEvidence` 中 `PROMO:` 前缀的项，原顺序；其余类别为空数组 |

金额只在 `claim` 顶层出现一次——`ask` 再抄一份，就必然出现"改一个忘一个"。

`basis` 永远是数组，永不为 `null`。缺货与价格类的政策依据写在 `narrative` 的散文里：政策不是证据，给它编号会污染指标 2 的分母。

### 2.2 narrative

模型输出契约，**只有一个字段**：

```json
{ "narrative": "<plain text>" }
```

- 3–5 个自然段，120–250 词；骨架写死在 system prompt 里：①这笔扣款是什么、争议金额、结论"not supported" ②依据，逐条点出并用 ID 标注 ③诉求（撤销扣款、开等额 Credit Memo）④收尾。
- 纯文本：不得以 `#`、`-`、`*`、`|` 开头，不得含三连反引号。主题行由 AR 在外面写，系统不产出。
- 正文里出现的每一个证据 ID token 必须属于 `claim.references ∪ claim.supportingEvidence ∪ claim.basis`，且**至少出现一个**。
- 词数越界不阻断（只进 §9 的结构完整率）；ID 越界与 markdown 标记阻断。

### 2.3 digest

- 覆盖集合恰好等于 `claim.references ∪ claim.supportingEvidence ∪ claim.basis` 去重后的结果，不多不少。
- 每项固定形状，按 `evidenceId` 排序：`{ "evidenceId": "...", "kind": "POD", "fields": { ... } }`。`fields` 按 kind 白名单（数量、日期、单价、条款号与条款原文截断），`null` 字段省略而不是写 `null`。
- **不含 `resolvedAt`**：文书已有 `drafted_at`，每项再带毫秒时间戳等于保证同一输入两次生成字节不同。
- `EDI812:` 不回查，从 Deduction 行合成——它是主张，不是单据。
- 对 `domain/DisputeDocument` 是不透明载荷：domain 的静态工厂不检查 digest 内容，否则域层又懂了一遍证据键。

ID 反解析规则（`EvidenceDigestBuilder`）：

| 前缀 | 回查 |
|---|---|
| `ASN:` / `BOL:` / `POD:` | `asn` / `bol` / `pod(shipmentId)` |
| `PRICE:sku:date` | 按**最后一个**冒号切成 `sku` 与 `date`，再 `priceAt(sku, date)`；`LocalDate` 里不会有冒号，`sku` 可能有 |
| `INVOICE:` | `invoiceLine(invoiceNo)` |
| `PROMO:docId·§clauseNo` | 按 `·§` 切，`clauses(docId)` 里按 `clauseNo` 找 |
| `SETTLE:` | `settlement(docId)` |
| `EDI812:` | 不回查 |

`EvidenceStore` 保持"只按自然键查"的现状不动——把 ID 反解析塞进 `persistence` 会让那个类同时懂两套键。

### 2.4 document view

两处同一形状，嵌套在 `document` 键下：

```json
"document": {
  "attemptNo": 1,
  "claim": { },
  "narrative": "...",
  "digest": [ ],
  "evidenceVersion": 1,
  "promptVersion": "sha256:...",
  "model": "...",
  "draftedAt": "2026-10-28T02:11:03Z"
}
```

`ActionOutcome` 不扩：web 层组 `DisputeResponse(ActionOutcome, DocumentView)`。`DetailView` 加可空 `document`。

可见期：**从 `DISPUTED` 起一直有效，包括 `WON` / `LOST` 之后**。模板判条件写 `th:if="${detail.document != null}"`，不要写成只认 `DISPUTED`。

## 3. 生成流程与事务边界

模型不能进事务。顺序固定为：

```text
guard   requireAwaitingAction → INCONCLUSIVE_CANNOT_DISPUTE     只读，不占事务
read    读取 verdict 行，断言 DISPUTABLE
digest  DisputeDocument / EvidenceDigestBuilder，无事务
draft   DraftingAgent，无事务，一次往返，12s 超时
tx      re-check requireAwaitingAction（窗口可能刚好过期、别人可能先提交）
        insert dispute → insert dispute_document → apply DISPUTE
```

- **守卫只有一份实现**：事务外那次直接调用现成的 `requireAwaitingAction`（包级可见），不在 `DisputeService` 里重写谓词。两份守卫迟早分叉，而分叉的表现是"事务外放过、事务内 500"。
- `attempt_no` 与 draft 轨迹的 `attemptNo` 都在写事务内从守卫结果重新取，不用事务外那次读的值。
- `ActionService` 是另一个 bean，代理生效，第二段仍是它自己的 `@Transactional`；不需要 `TransactionTemplate`，也不会踩 self-invocation。
- "全有或全无"由第二段单事务保证，不靠把模型包进事务。第一段一行未写。

**失败分档按责任方，不按步骤**：

| 情形 | 状态 | 码 |
|---|---|---|
| 模型不可用、超时、回非法 JSON、无 `ChatModel` | 503 | `DRAFT_FAILED` |
| 投影不变量违反、ID 回查不到、正文 ID 越界、正文 markdown 标记 | 500 | `DRAFT_INCONSISTENT` |

500 的语义是内部矛盾——重试永远不会成功，必须让 AR 看到 500 而不是无脑重试。`DRAFT_INCONSISTENT` 的异常必须带**是哪个 ID**。

失败不落行，状态停在 `AWAITING_ACTION`，AR 原样重试。并发重复提交由事务内重查守卫给 409。

`ApiException` 需要新增 `serviceUnavailable(code, message)` 与 `internal(code, message)` 两个工厂。

**超时**：复用 `EvaluationProperties.modelRoundTimeout()`（12s），不新增 20s 总截止——只有一轮，12s 就是总截止。一旦以后给文书加重试，总截止必须回来。

## 4. 数据模型

一个迁移 `V2__dispute_document.sql`，两表同生：

```sql
CREATE TABLE evidence_version (
    id         integer PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    version    integer NOT NULL,
    updated_at timestamptz NOT NULL
);
INSERT INTO evidence_version (id, version, updated_at) VALUES (1, 1, now());

ALTER TABLE dispute ADD CONSTRAINT dispute_deduction_id_key UNIQUE (deduction_id, id);

CREATE TABLE dispute_document (
    dispute_id       uuid PRIMARY KEY REFERENCES dispute (id),
    deduction_id     uuid NOT NULL,
    attempt_no       integer NOT NULL,
    claim            jsonb NOT NULL,
    narrative        text NOT NULL,
    digest           jsonb NOT NULL,
    evidence_version integer NOT NULL,
    prompt_version   text NOT NULL,
    model            text NOT NULL,
    drafted_at       timestamptz NOT NULL,
    FOREIGN KEY (deduction_id, attempt_no) REFERENCES verdict (deduction_id, attempt_no),
    FOREIGN KEY (deduction_id, dispute_id) REFERENCES dispute (deduction_id, id)
);
```

- `(deduction_id, id)` 的唯一约束让"文书挂到另一笔扣款的 dispute 上"在 DDL 层不可表示；顺带充当 `GET /deductions/{id}` 按 `deduction_id` 查文书的索引。`dispute.deduction_id` 本就是 `UNIQUE`，这条复合约束是白送的，它的全部用途是当那个复合外键的目标。
- `verdict` 的复合外键保证引用的是真实存在的那次判决。
- `dispute_id` 做主键即"一 dispute 一行"。
- **`DISPUTABLE` 不写成触发器**：它已由守卫保证，另有不变量 5 与两条测试。触发器会成为第三处规则，而且唯一一处"没跑起来也没人看得见"的。

**迁移规程**：任何改动证据种子数据的迁移必须显式执行

```sql
UPDATE evidence_version SET version = version + 1, updated_at = now();
```

并把 digest 的复算按版本对齐。证据表 append-only；没有这条规程，`evidence_version` 就退化成一个永远等于 1 的列。

## 5. 轨迹

- `type = "draft"`、`name = "drafter"`，与评估轮同表渲染，模板零改动。
- `attemptNo` 取 `source_attempt_no`（文书引用的那次判决），不是"当前 attempt"——`read` 按 `(deductionId, attemptNo)` 过滤，取错就落到另一张表里。
- `evidenceIds` 装 digest 的 ID 列表，页面"Evidence"列只放 ID；金额留在 `request` 里。
- 复用 `type` 而不是加 `phase` 列：`type` 的存在意义就是判别位。
- **draft trace 写在校验之前，失败不回滚。** 它在 Mongo，PG 事务管不到它，所以 503 与 500 也各留一条：`response` 空、`error` 有值。否则"模型不可用"这个最需要现场的失败在详情页上什么都看不到，`traceUnavailable` 那段就没有存在意义。

## 6. 回放 fixture

`scenarios.json` 只做增量：

```json
{
  "deductions": { "<uuid>": { "attempts": [ ... ] } },
  "drafts":     { "<uuid>": [ { "text": "{\"narrative\": \"...\"}" } ] }
}
```

- `drafts` 每个 deduction 一个**列表**，与 `attempts` 同构但扁平。AR 重试 = 新的 Drafter 会话（`assistantMessages == 0`）→ 计数器推到 2，没有列表重试写不进去。
- 哨兵是**共享常量** `shared/PromptMarker.DRAFT`，`DraftingAgent` 的 system prompt 末尾拼它、`ReplayChatModel` 用它分派。同一个编译期常量，没有第二份字面量。
- `ReplayChatModel` 先判哨兵再判 `EDI812:` 正则；两条路径的 id 通道仍是同一个正则（`claim.references` 无条件含 `EDI812:` 正是为此）。
- `drafts` 计数器与 `reset()` 一起清，否则用例之间串。

**为什么只有 `DISPUTE` 那一笔需要合法 `drafts` 条目**：现有 4 处 `/dispute` 调用里只有一笔走到 200，另外三处是 409 负路径。前提是守卫在生成之前——谁把顺序调反，负路径测试会因为缺 draft fixture 直接炸，而不是悄悄多花 3 秒。这是 §3 顺序的可观测后果。

### 6.1 三笔保留扣款

| 用途 | deduction id | fixture |
|---|---|---|
| 200 唯一路径 | `11111111-1111-1111-1111-111111111111` | 合法 narrative 一条；同方法内第二次调用 409 |
| 503 的 fixture 缺口 | `99999999-9999-9999-9999-999999999999`（PROMO，`DISPUTABLE`） | **故意不给 `drafts` 条目** |
| 500 的毒正文 | `55555555-5555-5555-5555-555555555555`（PRICE，`DISPUTABLE`） | 毒 narrative 一条（封闭集外 ID 或 markdown 标记） |

三笔各自在**独立测试方法**里只打一次 `/dispute`（同方法内第二次必然 409）。`@Sql` 每方法重跑 `seed.sql`，所以三笔可以跨测试类复用，但不能在一个方法里混用。`99999999` 与 `55555555` 的 `attempts` 已存在，`/evaluate` 能把它们推到 `AWAITING_ACTION`，不需要新评估 fixture；503 那笔连 `drafts` 键都不要建，500 那笔只建毒的那条。

不要把 `11111111` 那笔改成毒正文——那会顺手把 200 那条路径弄坏。

## 7. 详情页

在 Verdict 区块之后、Trace 之前插入：

- `<h2>Dispute document</h2>`
- 一行 `Dispute amount`，数字用 `tabular-nums`
- narrative 用 `<pre class="document-narrative">`，不算 `--muted`、不算 12px
- 新增三行样式：`.document-narrative { white-space: pre-wrap; font-size: 14px; line-height: 1.6; }`

不复用 `.trace-request`：那条样式是 12px 灰色等宽小字，把客户可见的正式文书渲染成调试输出，会毁掉 M4 唯一能拿给人看的东西。不动布局、不加动画、不加 JS；`apple-design` 在这里只剩"金额等宽数字"一条。

## 8. 文档同步

| 文件 | 改动 |
|---|---|
| `GLOSSARY.md` | 已加：Dispute Document / Narrative / Ask / Basis / Evidence Digest / `claim` 作用域条目 |
| `docs/adr/0008-*.md` | 新增 |
| `docs/ARCHITECTURE.md` | §争议文书改写；ADR 列表追加 0008 |
| `docs/PLAN.md` | M4 验收细化；指标 3 降级 |
| `docs/STATE-MACHINE.md` | 不变量 5 扩写（不新增第 8 条） |
| `README.md` / `README.zh.md` | 无需改动：两份都没有指标章节 |

## 9. 指标

指标 3 从"文书可用率（LLM judge）"降级为**文书结构完整率**（确定性）：五条硬校验全过、骨架四段齐备、词数在界内的文书占比。它不测模型质量——`claim` 由判决投影，它只反映管线。

LLM judge 的"文书可用率"降为人工抽检的观察项，输入口径固定为 `claim` + `narrative` + `digest` 三元组，不给自动闸门，不当主指标。

## 10. 测试 seam

本仓库的标准不是单 seam：现有六个非 HTTP 测试类（`ClosedSetTest`、`WindowArithmeticTest`、`StateMachineTest`、`MongoTraceRecorderTest`、`ReplayChatModelTest`、`EvaluationAgentPromptTest`）。M4 沿用这个形状，用三条：

| seam | 位置 | 断言什么 |
|---|---|---|
| 主 seam（HTTP） | `POST /deductions/{id}/dispute` + `GET /deductions/{id}`，外加一次 `jdbc` 直查 | 契约、五条硬校验、状态、轨迹、页面、指标 |
| 回放通道 | `ReplayChatModelTest`（现成） | `drafts` 的 key 取到的是对应那条 draft |
| 纯函数 | 新 `EvidenceReferenceTest`（表驱动）、新 `DrafterPromptTest`（与 `EvaluationAgentPromptTest` 同构） | ID 反解析边界、prompt 契约 |

`jdbc` 直查是主 seam 的一半，不是第四条 seam——`DISPUTED ⇒ 恰有一行 dispute_document` 只能直查，照 `PipelineIntegrationTest` 现有的 `jdbc` 写法。500 与 503 也走主 seam：它们是 `drafts` 的两种数据形态，不是两条新路径。

### 10.1 prompt 契约测试

正文规则（封闭集内 ID、禁 markdown、字数区间、不复述金额、骨架四段）只活在 system prompt 里，而 `ReplayChatModel` 从不读 prompt。prompt 漂了，全仓库没有任何测试会红。`DrafterPromptTest` 与 `EvaluationAgentPromptTest` 同构：断言契约字符串出现在发出去的 system message 里，且 `DraftOutput` 的 record component 名与契约逐字对应。

### 10.2 解析器边界

这里没有现成 parser 可复用：`EvidenceIds` 只有四个构造方向，`EvidenceStore` 全是自然键查询。要写的是**一个新静态函数** `parse(String) -> (表, 键)` 加一张表，不建通用 parser。种子数据里没有带冒号的 SKU，在主 seam 上造这个 case 得往 PG 插一行只服务解析用例的数据，比表驱动贵得多。表里至少覆盖 `PRICE:` 按最后一个冒号切、`PROMO:` 按 `·§` 切、`EDI812:` 不回查。

### 10.3 断言清单

1. 契约字符串出现在 system prompt 里；`DraftOutput` component 名逐字对应。
2. `drafterPromptVersion` 对整个 system message 取哈希（契约 + 骨架 + 政策全文），不复用 `EvaluationAgent.promptVersion`——差别在哈希的内容不同。
3. 正文 ID 扫描越界即失败；至少一个 ID 缺失即失败。
4. `claim.amount/currency == deduction.amount/currency` 不符即失败。
5. `basis ⊆ supportingEvidence ∪ references`，且 `basis` 永不为 `null`。
6. 200 路径：`DISPUTED`、`dispute_document` 恰一行、`claim` 与 verdict 逐字对齐、页面渲出 narrative。
7. 503 路径（`99999999`）：无 `drafts` 条目 → 503 `DRAFT_FAILED`，且同时断言 `dispute_document` 零行、`dispute` 零行、`deduction.version` 未变。
8. 500 路径（`55555555`）：毒正文 → 500 `DRAFT_INCONSISTENT`，消息含违规 ID 或规则名；三条一起断：`dispute_document` 零行、`dispute` 零行、`deduction` 仍是 `AWAITING_ACTION` 且 `version` 未变。单事务里写文书 + 转移，最常见的坏法是半成功（文书落库、转移没落，或反过来），只断文书行数对这种恰好无感。
9. `DISPUTED ⇒ 恰有一行 dispute_document`（`jdbc` 直查）。
10. 并发/重复提交：两个 `/dispute` 同时打同一笔，一个 200 一个 409，`dispute_document` 仍恰好一行。
11. `drafts` 的 key：给定 prompt 标记与 deduction id，`ReplayChatModel` 取到对应那条 draft，attemptNo 不与 evaluation 的重试通道相撞。
12. fixture 不串线：同一 deduction 先 `/evaluate`（attempt 1）再 `/dispute`，文书取 `drafts` 那份，评估计数器不受影响。
13. replay-only 跑通，无真实模型依赖。
14. 失败也有轨迹：500 之后 `GET /deductions/{id}` 的 trace 里有且只有一条 `type = "draft"` 且 `error` 非空。这条同时补上一个现存空洞——前面十三条里没有任何一条真的查过 trace，而用户故事要求"生成轨迹可单独查回"。

不含：UI 快照测试、模型质量测试。

## 11. 变更台账

- 新增：`V2__dispute_document.sql`、`agent/DraftingAgent`、`agent/EvidenceDigestBuilder`、`app/DisputeService`、`domain/DisputeDocument`、`domain/EvidenceReference`（静态 `parse`）、`shared/PromptMarker`、`web/DocumentView` / `DisputeResponse`、详情页一段模板 + 三行 CSS；测试侧 `EvidenceReferenceTest`、`DrafterPromptTest`，`PipelineIntegrationTest` / `ReplayChatModelTest` 扩条。
- 改动：`ActionService.dispute` 签名加 `DisputeDocument`；`DeductionController` 的 `/dispute` 改调 `DisputeService`；`DetailService`/`DetailView` 加 `document`；`ApiException` 加两个工厂；`ReplayScenario`/`ReplayChatModel` 加 `drafts` 通道。
- 不动：`close` / `result` 的响应与实现、状态机转移、窗口计算、三个 Validator、工具集。
