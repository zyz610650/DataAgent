/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.dataagent.workflow.node;

import com.alibaba.cloud.ai.dataagent.dto.planner.ExecutionStep;
import com.alibaba.cloud.ai.dataagent.dto.planner.Plan;
import com.alibaba.cloud.ai.dataagent.entity.UserPromptConfig;
import com.alibaba.cloud.ai.dataagent.enums.TextType;
import com.alibaba.cloud.ai.dataagent.prompt.PromptHelper;
import com.alibaba.cloud.ai.dataagent.service.llm.LlmService;
import com.alibaba.cloud.ai.dataagent.service.prompt.UserPromptService;
import com.alibaba.cloud.ai.dataagent.util.ChatResponseUtil;
import com.alibaba.cloud.ai.dataagent.util.FluxUtil;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.AGENT_ID;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLAN_CURRENT_STEP;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLANNER_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.RESULT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_EXECUTE_NODE_OUTPUT;

/**
 * 报告生成节点。
 *
 * 当前节点是多步分析链路的收尾阶段，负责把前面所有步骤的执行结果整合成一份面向用户的最终报告。
 *
 * 它不会再去执行数据查询，而是做三件事：
 * 1. 解析 Planner 输出的完整计划。
 * 2. 汇总每一步的执行结果与分析结果。
 * 3. 结合用户问题与提示词优化配置，生成最终 Markdown 报告。
 *
 * 这是“机器内部执行结果”向“用户可读结论”的最后一次翻译。
 */
@Slf4j
@Component
public class ReportGeneratorNode implements NodeAction {

	private final LlmService llmService;

	private final BeanOutputConverter<Plan> converter;

	private final UserPromptService promptConfigService;

	public ReportGeneratorNode(LlmService llmService, UserPromptService promptConfigService) {
		this.llmService = llmService;
		this.converter = new BeanOutputConverter<>(new ParameterizedTypeReference<>() {
		});
		this.promptConfigService = promptConfigService;
	}

	/**
	 * 生成最终报告。
	 *
	 * 这里会把中间状态重新整理成“用户需求 + 执行计划 + 每步结果”的大 Prompt，
	 * 然后交给模型输出 Markdown 格式的最终结论。
	 */
	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		String plannerNodeOutput = StateUtil.getStringValue(state, PLANNER_NODE_OUTPUT);
		String userInput = StateUtil.getCanonicalQuery(state);
		Integer currentStep = StateUtil.getObjectValue(state, PLAN_CURRENT_STEP, Integer.class, 1);

		@SuppressWarnings("unchecked")
		HashMap<String, String> executionResults = StateUtil.getObjectValue(state, SQL_EXECUTE_NODE_OUTPUT,
				HashMap.class, new HashMap<>());

		Plan plan = converter.convert(plannerNodeOutput);
		ExecutionStep executionStep = getCurrentExecutionStep(plan, currentStep);
		String summaryAndRecommendations = executionStep.getToolParameters().getSummaryAndRecommendations();

		// 报告提示词支持按智能体维度配置优化项；如果 AgentId 无法解析，则退化为全局配置。
		String agentIdStr = StateUtil.getStringValue(state, AGENT_ID);
		Long agentId = null;
		try {
			if (agentIdStr != null) {
				agentId = Long.parseLong(agentIdStr);
			}
		}
		catch (NumberFormatException ignore) {
			// 忽略解析失败，表示不按 Agent 维度取配置。
		}

		Flux<ChatResponse> reportGenerationFlux = generateReport(userInput, plan, executionResults,
				summaryAndRecommendations, agentId);

		TextType reportTextType = TextType.MARK_DOWN;

		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(this.getClass(),
				state, "开始生成报告...", "报告生成完成。", reportContent -> {
					log.info("Generated report content: {}", reportContent);
					Map<String, Object> result = new HashMap<>();
					result.put(RESULT, reportContent);

					// 报告产出后，把中间态清空，避免无关上下文继续遗留在后续状态里。
					result.put(SQL_EXECUTE_NODE_OUTPUT, null);
					result.put(PLAN_CURRENT_STEP, null);
					result.put(PLANNER_NODE_OUTPUT, null);
					return result;
				},
				Flux.concat(Flux.just(ChatResponseUtil.createPureResponse(reportTextType.getStartSign())),
						reportGenerationFlux,
						Flux.just(ChatResponseUtil.createPureResponse(reportTextType.getEndSign()))));

		return Map.of(RESULT, generator);
	}

	/**
	 * 根据当前步号获取报告步骤。
	 *
	 * 报告节点通常在执行计划末尾触发，因此这里取的往往是“最终总结步骤”，
	 * 它的 `summaryAndRecommendations` 会直接参与报告 Prompt 组织。
	 */
	private ExecutionStep getCurrentExecutionStep(Plan plan, Integer currentStep) {
		List<ExecutionStep> executionPlan = plan.getExecutionPlan();
		if (executionPlan == null || executionPlan.isEmpty()) {
			throw new IllegalStateException("Execution plan is empty");
		}

		int stepIndex = currentStep - 1;
		if (stepIndex < 0 || stepIndex >= executionPlan.size()) {
			throw new IllegalStateException("Current step index out of range: " + stepIndex);
		}

		return executionPlan.get(stepIndex);
	}

	/**
	 * 组织报告生成所需的完整 Prompt，并调用模型生成最终内容。
	 *
	 * `promptConfigService.getOptimizationConfigs(...)` 用于加载额外提示词优化项。
	 * 这是一种“可配置 Prompt 增强”机制，可以按场景或智能体细化报告风格与重点。
	 */
	private Flux<ChatResponse> generateReport(String userInput, Plan plan, HashMap<String, String> executionResults,
			String summaryAndRecommendations, Long agentId) {
		String userRequirementsAndPlan = buildUserRequirementsAndPlan(userInput, plan);
		String analysisStepsAndData = buildAnalysisStepsAndData(plan, executionResults);

		List<UserPromptConfig> optimizationConfigs = promptConfigService.getOptimizationConfigs("report-generator",
				agentId);

		String reportPrompt = PromptHelper.buildReportGeneratorPromptWithOptimization(userRequirementsAndPlan,
				analysisStepsAndData, summaryAndRecommendations, optimizationConfigs);
		log.debug("Report Node Prompt: \n {} \n", reportPrompt);
		return llmService.callUser(reportPrompt);
	}

	/**
	 * 构建“用户需求 + 计划概述”部分。
	 *
	 * 这一段让最终报告模型知道：
	 * - 用户原始问题是什么。
	 * - Planner 采用了怎样的思考过程。
	 * - 每一步打算调用什么工具完成什么事情。
	 */
	private String buildUserRequirementsAndPlan(String userInput, Plan plan) {
		StringBuilder sb = new StringBuilder();
		sb.append("## 用户原始需求\n");
		sb.append(userInput).append("\n\n");

		sb.append("## 执行计划概述\n");
		sb.append("**思考过程**: ").append(plan.getThoughtProcess()).append("\n\n");

		sb.append("## 详细执行步骤\n");
		List<ExecutionStep> executionPlan = plan.getExecutionPlan();
		for (int i = 0; i < executionPlan.size(); i++) {
			ExecutionStep step = executionPlan.get(i);
			sb.append("### 步骤 ").append(i + 1).append(": 步骤编号 ").append(step.getStep()).append("\n");
			sb.append("**工具**: ").append(step.getToolToUse()).append("\n");
			if (step.getToolParameters() != null) {
				sb.append("**参数描述**: ").append(step.getToolParameters().getInstruction()).append("\n");
			}
			sb.append("\n");
		}

		return sb.toString();
	}

	/**
	 * 构建“各步骤执行结果”部分。
	 *
	 * 这里会把 SQL 结果、Python 分析结果等中间态重新组织成统一的报告上下文，
	 * 让最终模型不仅知道结论，还知道结论来自哪些步骤和哪些数据。
	 */
	private String buildAnalysisStepsAndData(Plan plan, HashMap<String, String> executionResults) {
		StringBuilder sb = new StringBuilder();
		sb.append("## 数据执行结果\n");

		if (executionResults.isEmpty()) {
			sb.append("暂无执行结果数据\n");
		}
		else {
			List<ExecutionStep> executionPlan = plan.getExecutionPlan();
			for (int i = 0; i < executionPlan.size(); i++) {
				ExecutionStep step = executionPlan.get(i);
				String stepId = String.valueOf(i + 1);
				String stepKey = "step_" + stepId;
				String stepResult = executionResults.get(stepKey);
				String analysisResult = executionResults.get(stepKey + "_analysis");

				if ((stepResult == null || stepResult.trim().isEmpty())
						&& (analysisResult == null || analysisResult.trim().isEmpty())) {
					continue;
				}

				sb.append("### ").append(stepKey).append("\n");
				sb.append("**步骤编号**: ").append(step.getStep()).append("\n");
				sb.append("**使用工具**: ").append(step.getToolToUse()).append("\n");
				if (step.getToolParameters() != null) {
					sb.append("**参数描述**: ").append(step.getToolParameters().getInstruction()).append("\n");
					if (step.getToolParameters().getSqlQuery() != null) {
						sb.append("**执行 SQL**: \n```sql\n")
							.append(step.getToolParameters().getSqlQuery())
							.append("\n```\n");
					}
				}

				if (stepResult != null && !stepResult.trim().isEmpty()) {
					sb.append("**执行结果**: \n```json\n").append(stepResult).append("\n```\n\n");
				}
				if (analysisResult != null && !analysisResult.trim().isEmpty()) {
					sb.append("**Python 分析结果**: ").append(analysisResult).append("\n\n");
				}
			}
		}

		return sb.toString();
	}

}
