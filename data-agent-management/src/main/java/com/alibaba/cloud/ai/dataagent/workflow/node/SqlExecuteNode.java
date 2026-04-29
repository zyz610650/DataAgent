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

import com.alibaba.cloud.ai.dataagent.bo.DbConfigBO;
import com.alibaba.cloud.ai.dataagent.bo.schema.DisplayStyleBO;
import com.alibaba.cloud.ai.dataagent.bo.schema.ResultBO;
import com.alibaba.cloud.ai.dataagent.bo.schema.ResultSetBO;
import com.alibaba.cloud.ai.dataagent.connector.DbQueryParameter;
import com.alibaba.cloud.ai.dataagent.connector.accessor.Accessor;
import com.alibaba.cloud.ai.dataagent.constant.Constant;
import com.alibaba.cloud.ai.dataagent.dto.datasource.SqlRetryDto;
import com.alibaba.cloud.ai.dataagent.dto.planner.ExecutionStep;
import com.alibaba.cloud.ai.dataagent.enums.TextType;
import com.alibaba.cloud.ai.dataagent.prompt.PromptHelper;
import com.alibaba.cloud.ai.dataagent.properties.DataAgentProperties;
import com.alibaba.cloud.ai.dataagent.service.llm.LlmService;
import com.alibaba.cloud.ai.dataagent.service.nl2sql.Nl2SqlService;
import com.alibaba.cloud.ai.dataagent.util.ChatResponseUtil;
import com.alibaba.cloud.ai.dataagent.util.DatabaseUtil;
import com.alibaba.cloud.ai.dataagent.util.FluxUtil;
import com.alibaba.cloud.ai.dataagent.util.JsonParseUtil;
import com.alibaba.cloud.ai.dataagent.util.JsonUtil;
import com.alibaba.cloud.ai.dataagent.util.MarkdownParserUtil;
import com.alibaba.cloud.ai.dataagent.util.PlanProcessUtil;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLAN_CURRENT_STEP;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_EXECUTE_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_GENERATE_COUNT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_GENERATE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_REGENERATE_REASON;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_RESULT_LIST_MEMORY;

/**
 * SQL 执行节点。
 *
 * 这个节点承接 `SqlGenerateNode` 产出的 SQL，真正连数据库执行，并把结果写回工作流状态。
 *
 * 它有两层职责：
 * - 业务层：执行 SQL、保存结构化结果、推进步骤。
 * - 展示层：把执行中的提示、SQL 文本和结果集流式返回给前端。
 *
 * 这里采用“先执行业务，再组装展示流”的思路，核心目标是保证状态落盘和前端展示尽量一致。
 */
@Slf4j
@Component
@AllArgsConstructor
public class SqlExecuteNode implements NodeAction {

	private final DatabaseUtil databaseUtil;

	private final Nl2SqlService nl2SqlService;

	private final LlmService llmService;

	private final DataAgentProperties properties;

	private final JsonParseUtil jsonParseUtil;

	/**
	 * 图表推荐只取前若干行样本，避免把整个结果集都送给模型。
	 */
	private static final int SAMPLE_DATA_NUMBER = 20;

	/**
 * `apply`：执行当前类对外暴露的一步核心操作。
 *
 * 阅读这个方法时，建议同时关注它依赖了什么输入，以及结果最后会被哪一层继续消费。
 */
	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		Integer currentStep = PlanProcessUtil.getCurrentStepNumber(state);

		String sqlQuery = StateUtil.getStringValue(state, SQL_GENERATE_OUTPUT);
		sqlQuery = nl2SqlService.sqlTrim(sqlQuery);

		log.info("Executing SQL query: {}", sqlQuery);

		String agentIdStr = StateUtil.getStringValue(state, Constant.AGENT_ID);
		if (StringUtils.isBlank(agentIdStr)) {
			throw new IllegalStateException("Agent ID cannot be empty.");
		}

		Long agentId = Long.valueOf(agentIdStr);
		DbConfigBO dbConfig = databaseUtil.getAgentDbConfig(agentId);

		return executeSqlQuery(state, currentStep, sqlQuery, dbConfig, agentId);
	}

	/**
	 * 真正执行 SQL，并把结果包装成前端流与 Graph 状态。
	 *
	 * 处理顺序值得重点理解：
	 * 1. 准备数据库访问参数。
	 * 2. 在 `Flux.create` 内真正执行数据库查询。
	 * 3. 成功时写入结果集、步骤推进、重试状态重置。
	 * 4. 失败时写入 `SQL_REGENERATE_REASON`，交给 Dispatcher 决定是否回到 SQL 生成节点。
	 */
	@SuppressWarnings("unchecked")
	private Map<String, Object> executeSqlQuery(OverAllState state, Integer currentStep, String sqlQuery,
			DbConfigBO dbConfig, Long agentId) {
		DbQueryParameter dbQueryParameter = new DbQueryParameter();
		dbQueryParameter.setSql(sqlQuery);
		dbQueryParameter.setSchema(dbConfig.getSchema());

		Accessor dbAccessor = databaseUtil.getAgentAccessor(agentId);
		final Map<String, Object> result = new HashMap<>();

		Flux<ChatResponse> displayFlux = Flux.create(emitter -> {
			emitter.next(ChatResponseUtil.createResponse("开始执行 SQL..."));
			emitter.next(ChatResponseUtil.createResponse("执行 SQL 查询："));
			emitter.next(ChatResponseUtil.createPureResponse(TextType.SQL.getStartSign()));
			emitter.next(ChatResponseUtil.createResponse(sqlQuery));
			emitter.next(ChatResponseUtil.createPureResponse(TextType.SQL.getEndSign()));
			ResultBO resultBO = ResultBO.builder().build();

			try {
				ResultSetBO resultSetBO = dbAccessor.executeSqlAndReturnObject(dbConfig, dbQueryParameter);
				DisplayStyleBO displayStyleBO = enrichResultSetWithChartConfig(state, resultSetBO);
				resultBO.setResultSet(resultSetBO);
				resultBO.setDisplayStyle(displayStyleBO);

				String strResultSetJson = JsonUtil.getObjectMapper().writeValueAsString(resultSetBO);
				String strResultJson = JsonUtil.getObjectMapper().writeValueAsString(resultBO);

				emitter.next(ChatResponseUtil.createResponse("执行 SQL 完成"));
				emitter.next(ChatResponseUtil.createResponse("SQL 查询结果："));
				emitter.next(ChatResponseUtil.createPureResponse(TextType.RESULT_SET.getStartSign()));
				emitter.next(ChatResponseUtil.createPureResponse(strResultJson));
				emitter.next(ChatResponseUtil.createPureResponse(TextType.RESULT_SET.getEndSign()));

				Map<String, String> existingResults = StateUtil.getObjectValue(state, SQL_EXECUTE_NODE_OUTPUT,
						Map.class, new HashMap<>());
				Map<String, String> updatedResults = PlanProcessUtil.addStepResult(existingResults, currentStep,
						strResultSetJson);

				log.info("SQL execution successful, result count: {}",
						resultSetBO.getData() != null ? resultSetBO.getData().size() : 0);

				// 当前步骤实际执行过的 SQL 需要回写到步骤对象中，报告生成阶段会用它回放过程。
				ExecutionStep.ToolParameters currentStepParams = PlanProcessUtil.getCurrentExecutionStep(state)
					.getToolParameters();
				currentStepParams.setSqlQuery(sqlQuery);

				result.putAll(Map.of(SQL_EXECUTE_NODE_OUTPUT, updatedResults, SQL_REGENERATE_REASON,
						SqlRetryDto.empty(), SQL_RESULT_LIST_MEMORY, resultSetBO.getData(), PLAN_CURRENT_STEP,
						currentStep + 1, SQL_GENERATE_COUNT, 0));
			}
			catch (Exception e) {
				String errorMessage = e.getMessage();
				log.error("SQL execution failed - SQL as follows: \n {} \n ", sqlQuery, e);
				result.put(SQL_REGENERATE_REASON, SqlRetryDto.sqlExecute(errorMessage));
				emitter.next(ChatResponseUtil.createResponse("SQL 执行失败: " + errorMessage));
			}
			finally {
				emitter.complete();
			}
		});

		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(this.getClass(),
				state, v -> result, displayFlux);
		return Map.of(SQL_EXECUTE_NODE_OUTPUT, generator);
	}

	/**
	 * 基于 SQL 结果推测更适合的图表展示方式。
	 *
	 * 这是增强体验用的附加步骤，不是 SQL 执行成功的必要条件。
	 * 即使失败，也不应该影响主链路结果返回。
	 */
	private DisplayStyleBO enrichResultSetWithChartConfig(OverAllState state, ResultSetBO resultSetBO) {
		DisplayStyleBO displayStyle = new DisplayStyleBO();
		if (!this.properties.isEnableSqlResultChart()) {
			log.debug("Sql result chart is disabled, set display style as table default");
			displayStyle.setType("table");
			return displayStyle;
		}

		try {
			String userQuery = StateUtil.getCanonicalQuery(state);
			String sqlResultJson = JsonUtil.getObjectMapper()
				.writeValueAsString(resultSetBO.getData() != null
						? resultSetBO.getData().stream().limit(SAMPLE_DATA_NUMBER).toList() : null);

			String userPrompt = String.format("""
					# 正式任务

					<最新> 用户输入: %s
					样例数据: %s

					# 输出
					""", userQuery != null ? userQuery : "数据可视化", sqlResultJson);

			String fullPrompt = PromptHelper.buildDataViewAnalysisPrompt();
			String[] parts = fullPrompt.split("=== 用户输入 ===", 2);
			String systemPrompt = parts[0].trim();

			log.debug("Built chart config generation system prompt as follows \n {} \n", systemPrompt);
			log.debug("Built chart config generation user prompt as follows \n {} \n", userPrompt);

			String chartConfigJson = llmService.toStringFlux(llmService.call(systemPrompt, userPrompt))
				.collect(StringBuilder::new, StringBuilder::append)
				.map(StringBuilder::toString)
				.block(Duration.ofMillis(properties.getEnrichSqlResultTimeout()));
			if (chartConfigJson != null && !chartConfigJson.trim().isEmpty()) {
				String content = MarkdownParserUtil.extractText(chartConfigJson.trim());
				displayStyle = jsonParseUtil.tryConvertToObject(content, DisplayStyleBO.class);
				log.debug("Successfully enriched ResultSetBO with chart config: type={}, title={}, x={}, y={}",
						displayStyle.getType(), displayStyle.getTitle(), displayStyle.getX(), displayStyle.getY());
				return displayStyle;
			}
			log.warn("LLM returned empty chart config, using default settings");
		}
		catch (Exception e) {
			log.error("Failed to enrich ResultSetBO with chart config", e);
		}
		return null;
	}

}
