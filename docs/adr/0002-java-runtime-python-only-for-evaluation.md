# 单一 Java 运行时，Python 只做离线评估

Status: accepted

运行时全部放在一个 Spring Boot 进程里（Spring AI 承担 tool calling、RAG 与结构化输出），Python 只负责合成数据生成与评估脚本，不参与运行时，两者之间没有 IPC。原方案是"Python Agent Core + Java 集成层"的双服务架构，被放弃的原因是：跨进程的一致性、超时、部分失败这些问题的调试成本，对一个 0 年经验的作者来说，高于它能带来的架构叙事价值。

这个决定成立的前提是 Spring AI 2.0 已经能在 Java 侧独立完成全部 AI 能力——2025 年写原方案时这个前提还不成立。

## Considered Options

- **双服务（Python agent + Java API）**：更贴近 JD 里"Java 为主 + Python 做 AI"的表述，但要多写约 600 行胶水代码，且引入一类作者无法在面试中辩护的失败模式。
- **纯 Python**：与 JD 的"Proficiency in Java"冲突，直接排除。

## Consequences

- 失去"MCP 是进程边界"这个架构理由，MCP 降级为一个可选的对外暴露面（把工具注册成 MCP Server），不再是必做项。
- README 必须解释"为什么评估留在 Python"，否则会被读成两套技术栈随意拼凑。
- `Orchestrator / Validator / Drafter` 要用 Spring AI 的 Java 写法实现，可参考的教程远少于 Python 生态。
