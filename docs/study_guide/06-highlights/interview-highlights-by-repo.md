# 仓库亮点与面试讲法总结

这篇不是重复讲功能，而是帮你把项目讲成“可复述的亮点专题”。

## 1. 亮点一：不是单轮 Text-to-SQL，而是可编排的数据分析 Agent

### 为什么值得讲

很多项目只做到“问题 -> SQL -> 结果”。这个仓库把计划、执行、Python、报告都串起来了。

### 它解决了什么问题

- 单步 SQL 解决不了复杂分析
- 过程不可见、不可控
- 错误难局部修复

### 可以怎么讲

“我们不是直接让模型出答案，而是先让模型出计划，再由执行器按步骤路由到 SQL、Python、报告节点。”

### 关键源码落点

- [DataAgentConfiguration.nl2sqlGraph(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java)
- [PlannerNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PlannerNode.java)
- [PlanExecutorNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PlanExecutorNode.java)

## 2. 亮点二：Graph 编排把“计划生成、计划推进、节点跳转”三层拆开

### 为什么值得讲

这是架构味最强的一点。

### 它解决了什么问题

- 否则所有逻辑会挤进一个大 service
- 很难支持重试、人工审核、局部回退

### 可以怎么讲

“节点只做业务，dispatcher 只做跳转，state 只做共享上下文，所以执行链路既可观察又可修。”

### 关键源码落点

- [PlanExecutorNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PlanExecutorNode.java)
- [PlanExecutorDispatcher](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/dispatcher/PlanExecutorDispatcher.java)
- [SQLExecutorDispatcher](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/dispatcher/SQLExecutorDispatcher.java)

## 3. 亮点三：RAG 不是直接查知识库，而是“业务证据召回 + Schema 召回”

### 为什么值得讲

这比“挂了一个向量库”更有工程价值。

### 它解决了什么问题

- 业务术语和表结构不是同一类知识
- 直接全库检索噪声很大

### 可以怎么讲

“我们把证据召回和 Schema 召回拆开，先补业务语义，再补表和字段上下文，最后由 TableRelationNode 把它们整理成可执行 SchemaDTO。”

### 关键源码落点

- [EvidenceRecallNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/EvidenceRecallNode.java)
- [SchemaRecallNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SchemaRecallNode.java)
- [TableRelationNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/TableRelationNode.java)

## 4. 亮点四：Structured Output 用的是“约束 + 兜底修复”双保险

### 为什么值得讲

这比只会说“我们用了 JSON 输出”更像实战项目。

### 它解决了什么问题

- 模型 JSON 输出不稳定
- 一次解析失败会拖垮整条链

### 可以怎么讲

“我们前面用 BeanOutputConverter 约束格式，后面再用 JsonParseUtil 做 JSON 修复，这样计划、意图识别、图表配置这类结构化输出更稳。”

### 关键源码落点

- [PlannerNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/PlannerNode.java)
- [PromptHelper](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/prompt/PromptHelper.java)
- [JsonParseUtil](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/util/JsonParseUtil.java)

## 5. 亮点五：前端不是等结果，而是全链路流式可观察

### 为什么值得讲

这体现了用户体验和执行可观测性的设计。

### 它解决了什么问题

- 链路长时用户没有反馈
- 中间步骤不可见
- 失败点难定位

### 可以怎么讲

“我们把模型流、节点流、SSE 流拆成三层，用 FluxUtil 做桥接，前端可以实时看到计划、SQL、结果集、报告生成过程。”

### 关键源码落点

- [GraphController](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java)
- [GraphServiceImpl](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)
- [FluxUtil](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/util/FluxUtil.java)

## 6. 亮点六：支持人工反馈中断恢复，不是伪恢复

### 为什么值得讲

这非常贴近企业实际。

### 它解决了什么问题

- 用户想在计划阶段介入
- 审核通过后不想整链重跑

### 可以怎么讲

“图在 HUMAN_FEEDBACK_NODE 前中断，用户反馈后我们通过 updateState 恢复原 thread 继续执行，而不是重新从头跑。”

### 关键源码落点

- [GraphServiceImpl.handleHumanFeedback(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java)
- [HumanFeedbackNode](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/HumanFeedbackNode.java)
- [HumanFeedbackDispatcher](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/dispatcher/HumanFeedbackDispatcher.java)

## 7. 亮点七：模型注册与切换被收口到统一基础设施层

### 为什么值得讲

这体现了 AI 基础设施设计能力，而不只是业务流程编排。

### 它解决了什么问题

- 模型配置动态切换
- 不同供应商统一接入
- 出站 HTTP、代理、路径覆盖统一治理

### 可以怎么讲

“业务节点永远只依赖 LlmService，真正的模型切换由 AiModelRegistry 和 DynamicModelFactory 完成，EmbeddingModel 还用了动态代理兼容 Starter 启动期。”

### 关键源码落点

- [AiModelRegistry](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/AiModelRegistry.java)
- [DynamicModelFactory](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/aimodelconfig/DynamicModelFactory.java)
- [DataAgentConfiguration.embeddingModel(...)](../../../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java)

## 8. 如果你要 3 分钟讲这个项目

可以按这条线讲：

1. 这是一个基于 Spring AI Alibaba Graph 的数据分析 Agent 后端
2. 它不是直接出 SQL，而是先出计划，再由执行器分发到 SQL、Python、报告链
3. 它把业务知识召回和 Schema 召回拆开，提升 NL2SQL 上下文质量
4. 它支持全链路流式输出、人工反馈中断恢复、模型动态切换和 MCP Tool 接入

## 9. 建议连读

- [总教程](../01-overview/total-tutorial.md)
- [主链路逐步执行图解](../04-main-chain/main-chain-step-by-step.md)
- [MCP / Tool / 多轮上下文 / 人工反馈恢复 深挖](../03-deep-dives/mcp-tool-multiturn-human-feedback-deep-dive.md)
