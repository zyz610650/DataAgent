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
import com.alibaba.cloud.ai.dataagent.dto.schema.SchemaDTO;
import com.alibaba.cloud.ai.dataagent.enums.TextType;
import com.alibaba.cloud.ai.dataagent.prompt.PromptConstant;
import com.alibaba.cloud.ai.dataagent.properties.CodeExecutorProperties;
import com.alibaba.cloud.ai.dataagent.service.llm.LlmService;
import com.alibaba.cloud.ai.dataagent.util.ChatResponseUtil;
import com.alibaba.cloud.ai.dataagent.util.FluxUtil;
import com.alibaba.cloud.ai.dataagent.util.MarkdownParserUtil;
import com.alibaba.cloud.ai.dataagent.util.PlanProcessUtil;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.PYTHON_EXECUTE_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PYTHON_GENERATE_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PYTHON_IS_SUCCESS;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PYTHON_TRIES_COUNT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_RESULT_LIST_MEMORY;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.TABLE_RELATION_OUTPUT;

/**
 * Python 代码生成节点。
 *
 * 当 Planner 判断当前步骤更适合用 Python 对 SQL 结果做进一步加工时，就会进入这个节点。
 * 典型场景包括：
 * - 对 SQL 结果做复杂统计、聚合或二次清洗。
 * - 生成图表前的数据变换。
 * - 需要用程序式逻辑补充单条 SQL 难以表达的分析过程。
 *
 * 这里并不直接运行 Python，只负责把上下文交给模型，生成一段待执行的 Python 代码。
 */
@Slf4j
@Component
public class PythonGenerateNode implements NodeAction {

	/**
	 * 为了控制 Prompt 体积，只给模型展示有限条样例数据。
	 */
	private static final int SAMPLE_DATA_NUMBER = 5;

	private final ObjectMapper objectMapper;

	private final CodeExecutorProperties codeExecutorProperties;

	private final LlmService llmService;

	public PythonGenerateNode(CodeExecutorProperties codeExecutorProperties, LlmService llmService) {
		this.codeExecutorProperties = codeExecutorProperties;
		this.llmService = llmService;
		this.objectMapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
	}

	/**
	 * 生成 Python 代码。
	 *
	 * 输入上下文包括：
	 * - 当前可用的数据库 Schema。
	 * - 前面 SQL 步骤已经产出的结果样例。
	 * - 当前计划步骤里对 Python 工具的说明。
	 * - 若上一轮 Python 执行失败，则附带上次代码和错误信息，让模型基于失败原因修复。
	 */
	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		SchemaDTO schemaDTO = StateUtil.getObjectValue(state, TABLE_RELATION_OUTPUT, SchemaDTO.class);
		List<Map<String, String>> sqlResults = StateUtil.hasValue(state, SQL_RESULT_LIST_MEMORY)
				? StateUtil.getListValue(state, SQL_RESULT_LIST_MEMORY) : new ArrayList<>();
		boolean codeRunSuccess = StateUtil.getObjectValue(state, PYTHON_IS_SUCCESS, Boolean.class, true);
		int triesCount = StateUtil.getObjectValue(state, PYTHON_TRIES_COUNT, Integer.class, 0);

		String userPrompt = StateUtil.getCanonicalQuery(state);
		if (!codeRunSuccess) {
			// 上一轮代码执行失败时，把失败代码和错误信息反馈给模型，让模型做“带错误上下文的修复生成”。
			String lastCode = StateUtil.getStringValue(state, PYTHON_GENERATE_NODE_OUTPUT);
			String lastError = StateUtil.getStringValue(state, PYTHON_EXECUTE_NODE_OUTPUT);
			userPrompt += String.format("""
					上次尝试生成的 Python 代码运行失败，请你重新生成符合要求的 Python 代码。
					【上次生成代码】
					```python
					%s
					```
					【运行错误信息】
					```
					%s
					```
					""", lastCode, lastError);
		}

		ExecutionStep executionStep = PlanProcessUtil.getCurrentExecutionStep(state);
		ExecutionStep.ToolParameters toolParameters = executionStep.getToolParameters();

		// 这里把执行环境约束也写进 Prompt，例如内存和超时时间，
		// 目的是让模型从生成阶段就避免产出超资源限制的代码。
		String systemPrompt = PromptConstant.getPythonGeneratorPromptTemplate()
			.render(Map.of("python_memory", codeExecutorProperties.getLimitMemory().toString(), "python_timeout",
					codeExecutorProperties.getCodeTimeout(), "database_schema",
					objectMapper.writeValueAsString(schemaDTO), "sample_input",
					objectMapper.writeValueAsString(sqlResults.stream().limit(SAMPLE_DATA_NUMBER).toList()),
					"plan_description", objectMapper.writeValueAsString(toolParameters)));

		Flux<ChatResponse> pythonGenerateFlux = llmService.call(systemPrompt, userPrompt);

		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(this.getClass(),
				state, aiResponse -> {
					// 某些模型即使提示词要求“只输出代码”，仍然会额外包上一层 Markdown 代码块。
					// 因此这里先裁剪代码块边界，再提取原始文本。
					aiResponse = aiResponse.substring(TextType.PYTHON.getStartSign().length(),
							aiResponse.length() - TextType.PYTHON.getEndSign().length());
					aiResponse = MarkdownParserUtil.extractRawText(aiResponse);
					log.info("Python Generate Code: {}", aiResponse);
					return Map.of(PYTHON_GENERATE_NODE_OUTPUT, aiResponse, PYTHON_TRIES_COUNT, triesCount + 1);
				},
				Flux.concat(Flux.just(ChatResponseUtil.createPureResponse(TextType.PYTHON.getStartSign())),
						pythonGenerateFlux,
						Flux.just(ChatResponseUtil.createPureResponse(TextType.PYTHON.getEndSign()))));

		return Map.of(PYTHON_GENERATE_NODE_OUTPUT, generator);
	}

}
