# 评估语料、四态标注与指标口径

Status: accepted

M6 的语料与指标口径一次性定死：约 100 条合成语料由 oracle 生成标注，oracle 开工前冻结；标注从三态改为四态；三条指标的分子分母、排除规则、报告形态全部写死。理由只有一条——这些数字一旦可以在事后解释，评估就退化成自证。

## 语料与 oracle

- **语料在 `src/main/resources/eval/corpus/`，一条一个 JSON 文件，文件名排序即固定顺序。** 放在 `test` 下，`eval:live` 在 main 作用域跑就读不到。loader 也只有一份，放 `src/main/java`，JUnit 与 `eval:live` 都调它。它与 `src/test/resources/eval/baseline/` 是入库的冻结 fixture，这与 `AGENTS.md` 里"禁止提交生成数据"的例外一致。
- **时间是相对偏移，不是绝对时间戳。** JSON 存 `receivedDaysAgo` 这类偏移，loader 用注入的 `Clock` 解成 `Instant`。`eval:live` 用真时钟，CI 用固定的 `2026-03-10T06:59:00Z`。写死绝对时间必然一边废掉：要么 live 里全部过期，要么 CI 结果依赖墙钟。
- **语料引用的商户与原因码属于全局参考数据，由 migration 维护**（`V3__reference_data.sql` 写入 `retailer` 与 `reason_code_mapping`）。它与 `src/test/resources/seed.sql` 是幂等叠加关系：两边都只插不删、按主键或业务键 `ON CONFLICT DO NOTHING`，谁先谁后都不冲突。有一条防漂移测试扫描语料里去重后的 `retailerId` / `reasonCode`，任一值不在 V3 里即红并点名 `caseId`。
- **`caseId` 固定，扣款主键由 `UUID.nameUUIDFromBytes(caseId)` 确定性派生。** 用 JDK 自带的 v3，不手写 v5 的 SHA-1 加版本位手术；配一条 golden UUID 断言防命名空间漂移，命名空间常量固定在 loader 里。
- **oracle 在 `src/main/java/com/deductionaudit/eval/oracle`**，只 import `domain` 的枚举，**禁止 import `agent/validators`**——那会变成用同一份实现测自己。它读的是与 prompt 同一个 `classpath:policy/audit-policy.md`，同源不需要额外机制。
- **政策 hash 是 oracle 源码里的编译期常量**，不是旁挂的 `.sha256` 文件。放文件里，改政策的人顺手也能改 hash，闸门就没了。放源码里，政策一改必然产生一次可见的代码 diff，oracle 拒绝运行直到有人确认。常量存**全量 sha256**，启动时断言它的前 12 位等于 `PolicyProvider.version()`（`new PolicyProvider()` 无依赖，`EvaluationAgent.policyVersion()` 只是原样委托它）。常量算法钉死为 `PolicyProvider.sha256(text)`：不要用 `Files.readAllBytes` 走原始字节——`PolicyProvider` 是对解码后的字符串再编码求哈希，两条路径在 BOM 或异常字节上会分叉，而这个常量的全部意义就是与它同源。
- **标注由 oracle 生成，人不写答案。** 人只设计冲突结构，变量由带种子的生成器随机化；种子写进每个 case 的 JSON。生成只跑一次，提交后的 JSON 是唯一事实源，CI 不重跑生成器。
- **"由 oracle 生成"必须由一条对账测试兜住，否则它只是一句声明。** 测试对全部语料重跑 oracle，逐字段比对 JSON 里的 `expectedState` / `expectedParkedReason` / `expectedSupportingEvidence` / `oracleBranch`，不一致即红。改了 oracle 规则却忘了重生成标注，红的就是这条。
- **空集写成空数组，不省略字段。** `POLICY_GAP` 与"证据缺失"两格的 `expectedSupportingEvidence` 一律 `[]`，否则对账测试天天在 `null` 与 `[]` 之间抖。
- **第 4 行（自相矛盾·有规则可裁）的裁向比例写死**：每格 6–7 条里**至少 2 条**裁到 `DISPUTABLE`。它同时供给指标 2 与指标 3 的分母，不定死的话分母随随机数在 18–21 与 36–42 之间漂。

## 四态标注

`PLAN.md` 原来的三态把"证据缺失"和"无规则可裁"合并成"证据不足"。后果是对无规则 case 硬报"缺证据"也算判对——而那正是最容易被做假的一格。改成四态：**扣款成立 / 可争议 / 证据不足·缺证据 / 证据不足·无规则**。

成因不需要新的判定逻辑：`ParkedReason.forInconclusive` 已经分开——`missingEvidence` 非空 → `EVIDENCE_GAP`，为空 → `POLICY_GAP`。指标 1 比对 `(verdict, parkedReason)`，所以对无规则 case 的断言就是免费的：`parkedReason == POLICY_GAP`。

防兜底另加一条：oracle 里 `INCONCLUSIVE` + 空 `missingEvidence` 即"无规则可裁"（`POLICY_GAP`），它只有两种成因——**同级规则对同一事实给出相反结论**（`PolicyGapCause.SAME_LEVEL_CONFLICT`）、**在政策中找不到覆盖该事实的规则**（`PolicyGapCause.NO_RULE_COVERS_THE_FACTS`）。两个枚举值就是全部合法分支，每个 `oracleBranch` 都必须登记到其中一个；未登记的 branch 在运行时直接抛异常。**禁止匿名分支，也禁止兜底分支**——"其他情况一律 `POLICY_GAP`"这种写法会让无规则可裁重新变成不可数。每条 case 把命中的分支名写进 NDJSON，CI 断言无规则格的每一条都命中这张闭合表里的某个分支。再抽 3 条做盲判复核。

## 指标 1：四态比对

- **头条是 micro exact-match**，按 case 加权。
- **四个状态各算 one-vs-rest recall；三个 category 各算 exact-match。** 两套分开报，recall 永不再平均成一个总数——一平均就掩盖某一格全部答错。
- **报 `k/n`，不报一位小数百分比。** 每桶 n≈20–26，95% 区间约 ±20 个百分点；"0.82" 会被读成精确测量。
- **15 格 × 4 态混淆表写进 `metrics.json` 附录**，格级只作诊断，不设闸门（每格 n=6–7）。
- **四态桶为 0 直接 fail，不渲染成 `n/a`。** 那说明语料缺格，是 bug。

## 指标 2：只在 `claim.supportingEvidence` 上算

- **分母只取 oracle 判 `DISPUTABLE` 的 case**（矩阵第 1 行全部 + 第 4 行裁到可争议的那半边，即第 1 行约 20 条、第 4 行三类各至少 2 条，n≈26）。`claim` 只在起草路径被构造，全仓库唯一调用点是 `DisputeService`；判"扣款成立"的 case 压根没有 claim。
- 剔除必须显式，报告按理由列三行：**无 claim（扣款成立）**、**字段不对（证据缺失，那一格该看 `missingEvidence` 而不是 `supportingEvidence`）**、**无规则可裁（正反面都没有规则，罚它就是隐式造一条规则）**。三行条数相加 + 分母必须等于**语料总条数**（90–105，按实际生成结果，不是假定 100）。
- **越界引用不是指标 2 的失败项，它在指标 2 里不可达。** `ClosedSet.assertClosedSet` 在 `EvaluationAgent` 解析判决时就抛了，越界的 verdict 存不下来，也就没有 claim。它的实际作用是给剔除行命名：这类 case 落进"无 claim"那一行。别在报告里找一个永远不会出现的数。
- **0/0 不许默认 1.0。** 模型一条都不引时 `precision` 记 `undefined` 并计入"零引用"，`recall` 记 0。

## 指标 3：两个数，永不相乘

- **头条 = 产出的文书中结构合格占比**，分母只含实际产出的文书，守住"这个数只反映管线"（[ADR 0008](./0008-dispute-document-projection-and-narrative.md)）。
- **第二个数 = 产出率**，实际产文书数 / oracle 判 `DISPUTABLE` 的条数，让"该产没产"可见。两个数并列报，不相乘。
- **骨架按字符偏移的先后判，不按段索引。** 契约允许 3–5 段，骨架是 4 部分；按段索引会把合法的 3 段文书系统性判成 3/4。判据：金额在最前、证据 ID 居中、`reverse` 与 `credit memo` 在后、收尾在最后。
- **P4 以可判形为准：ask 之后出现收尾标记，且标记之后不再出现任何证据 ID。** `reverse` 按词边界匹配（`\breverse\b`），不命中论证段里的 `reversed`；收尾标记是固定集合，命中后才算收尾。提示词里另有两句约束——收尾段不超过 25 词、不含数字——它们约束的是模型行为，**不进指标**：收尾段在 3–5 段契约下无法独立切分，"25 词"是可调的经验值而非可判事实。这是首次 live 复盘后定的口径：判据修正前后各有一次结果，修正前 11/21 为判据两处假阴性所致，修正后 20/21，剩余 1 条是模型把金额写了两次。
- **五条硬校验不进这个比率。** 它们是写入时的闸门：违反的文书根本不落库（走 500），已存文书恒为 100%。算进比例等于给分子加常数，它是门，由集成测试守。
- 词数与段数界、分词器（空白切分）、金额字符串由导出器写进 NDJSON 的 `contract` 字段，Python 只执行，不各写一份。

## NDJSON 契约与归一化

`eval:live` 与 CI 的 fixture 路径**只差 profile**（live 与 replay），导出器是同一个类，NDJSON 只有一份格式。全量行一行一 case，字段：`schemaVersion` / `caseId` / `attemptNo` / `status` / `error` / `parkedReason` / `verdict` / `claim` / `narrative` / `digest` / `latency` / `toolCalls` / `contract` / `oracleBranch` / `expectedState` / `expectedParkedReason` / `expectedSupportingEvidence`。三个 `expected*` 字段让每行自包含——第三方脚本只吃 `results.ndjson` 就能复算指标 1 与指标 2；**基线行也保留它们**，静态标签不影响确定性，反而让基线 diff 顺带盯住标注漂移。

- **全量行与基线行是同一套导出器的两种输出模式，不是两套格式。** 导出器只产全量行；**写基线文件时**套用白名单：去 `latency`、去每个 toolCall 的 `duration`（只留 `name` 与 `count`）、去 timestamp、trace id、运行时生成的 verdict/attempt UUID。`docs/eval/reports/*/results.ndjson` 保留 `latency`，供人读。
- 不这么写清，第一次 CI 必红在 `latency` 上，然后有人把检查删了——正是本 ADR 反复警告的那个失败模式。白名单属于导出器，不属于 diff 脚本。
- **指标代码只有一份，在 Python 侧。** Java 只负责跑管线并导出 NDJSON，两边都吃同一个文件——不许"harness 读 PG、CI 读文件"两条输入路径。
- **单 case driver 是两段**：`EvaluationService.evaluate`，若 `DISPUTABLE` 再 `DisputeService.draft`，然后导出。只打 evaluate 的话指标 2/3 分母为 0，报告空转。并发 N ≤ 8，受 Hikari 默认 10 约束。
- **`schemaVersion` 每行都带**，不是文件头；diff 前先比版本再比内容。字段增删改名即 +1，`metrics.json` 与每行 NDJSON 同版本。边界：`schemaVersion` 描述的是 NDJSON **行契约**，`metrics.json` 从属于同一版本；只在 `metrics.json` 顶层新增派生字段（如 `negativeControls`）、而 15 行逐字节未变时，不动版本号。**`schemaVersion` 2 对应本次新增的 `error` 字段**：起草失败时写明原因，不再静默降级成一个空 narrative。`error` 的值只允许"稳定码 + 固定文本"（如 `DRAFT_UNAVAILABLE: draft missing`），不含 attempt 序号、时间戳或任何运行时变量——序号只出现在 `attemptNo` 里，否则基线每跑一次就抖一次。

## CI 与存档

- CI 跑 15 条全覆盖子集（三类 × 五种情形各 1 条），清单是 `src/test/resources/eval/ci-manifest.json`，每项为 `{caseId, why?}`：正例不写 `why`，负向对照必须写清意图，语料正文仍从 main 的那一份读，不造第二套。
- **cassette 分 `verdict` 与 `drafts` 两个 channel，除负向对照外，manifest 里每个 `DISPUTABLE` 的 case 两路都必须有条目。** 少了 `drafts` 又不标 `why`，起草那段直接走 503，指标 3 头条的分母是 0，而报告只会显示一个空转的 100%；标了 `why` 的就是负向对照，产出率的分母照算、分子少 1，`error` 与 `narrative: null` 必须同时出现在该行。
- **失败条目必须带 `why`**，写成负向对照（例如"模型引用封闭集外 ID，必须被 validator 拒"），并给 `expected: {status, parkedReason}`。CI 断言混淆矩阵逐格一致，断的不是"分数 ≥ X"。没标 `why` 的低基线就是把草率固化。
- 快照 diff 的是归一化后的 NDJSON 与 `metrics.json`，提交在 `src/test/resources/eval/baseline/`。
- **报告存档进仓库**：`docs/eval/reports/<YYYY-MM-DD>-<sha>/` 下 `report.md` + `metrics.json` + `results.ndjson`。`metrics.json` 钉住 corpus 的 sha256、`policy/audit-policy.md` 的 sha256、commit sha、`schemaVersion` 四项，否则一年后语料改过，旧报告的分母无法复原。CI 只 diff，不写报告；报告由人跑 `eval:live` 后提交。
- **live 必须在干净的库上跑，收尾 `docker compose down -v`。** live 会把真实行写进共享库：`caseId` 派生出的主键是确定的，第二次在同一套库上跑必然主键冲突，或在残留状态上继续，得到无法复现的结果。存档里的 `results.ndjson` 因此只有在"空库 + 该 commit"下才可重放。

## Consequences

- 三态的任何一处残留（文档、报表、测试名）都会让"无规则可裁"那一格重新变成不可见，改标注时要一次改净。
- CI 绿灯仍然只说管线正确（[ADR 0007](./0007-ci-runs-pipeline-not-model.md)）：基线由人手写的 cassette 决定，不是模型的成绩单。
- oracle 冻结后，任何一次 `audit-policy.md` 的改动都会同时打断 oracle 与全部回放，这两件事本来就该一起发生。
- holdout 不在本 spec 内，延后至 M7，且永远只做 `eval:live` 的人工观察项——它是语义判定，CI 里没有模型。
