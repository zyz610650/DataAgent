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

import com.alibaba.cloud.ai.dataagent.enums.TextType;
import com.alibaba.cloud.ai.dataagent.properties.CodeExecutorProperties;
import com.alibaba.cloud.ai.dataagent.service.code.CodePoolExecutorService;
import com.alibaba.cloud.ai.dataagent.util.ChatResponseUtil;
import com.alibaba.cloud.ai.dataagent.util.FluxUtil;
import com.alibaba.cloud.ai.dataagent.util.JsonParseUtil;
import com.alibaba.cloud.ai.dataagent.util.JsonUtil;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.PYTHON_EXECUTE_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PYTHON_FALLBACK_MODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PYTHON_GENERATE_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PYTHON_IS_SUCCESS;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PYTHON_TRIES_COUNT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_RESULT_LIST_MEMORY;

/**
 * Python 执行节点。
 *
 * 这一层真正把模型生成的 Python 代码交给代码执行池运行，并把执行结果写回 Graph 状态。
 *
 * 为什么系统要把“生成代码”和“执行代码”拆成两个节点：
 * - 生成阶段只负责让模型产出代码。
 * - 执行阶段负责与代码沙箱/执行池集成，处理 stdout、stderr、异常、重试和降级。
 * - 分层之后，失败重试策略会清晰很多。
 */
@Slf4j
@Component
public class PythonExecuteNode implements NodeAction {

	private final CodePoolExecutorService codePoolExecutor;

	private final ObjectMapper objectMapper;

	private final JsonParseUtil jsonParseUtil;

	private final CodeExecutorProperties codeExecutorProperties;

	public PythonExecuteNode(CodePoolExecutorService codePoolExecutor, JsonParseUtil jsonParseUtil,
			CodeExecutorProperties codeExecutorProperties) {
		this.codePoolExecutor = codePoolExecutor;
		this.objectMapper = JsonUtil.getObjectMapper();
		this.jsonParseUtil = jsonParseUtil;
		this.codeExecutorProperties = codeExecutorProperties;
	}

	/**
	 * 执行 Python 代码，并把 stdout 或失败信息写回状态。
	 *
	 * 关键输出字段：
	 * - `PYTHON_EXECUTE_NODE_OUTPUT`：标准输出或错误信息。
	 * - `PYTHON_IS_SUCCESS`：本轮执行是否成功。
	 * - `PYTHON_FALLBACK_MODE`：当重试次数耗尽后，是否进入降级模式。
	 */
	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		try {
			String pythonCode = StateUtil.getStringValue(state, PYTHON_GENERATE_NODE_OUTPUT);
			List<Map<String, String>> sqlResults = StateUtil.hasValue(state, SQL_RESULT_LIST_MEMORY)
					? StateUtil.getListValue(state, SQL_RESULT_LIST_MEMORY) : new ArrayList<>();

			int triesCount = StateUtil.getObjectValue(state, PYTHON_TRIES_COUNT, Integer.class, 0);

			CodePoolExecutorService.TaskRequest taskRequest = new CodePoolExecutorService.TaskRequest(pythonCode,
					objectMapper.writeValueAsString(sqlResults), null);

			// `CodePoolExecutorService` 是真正对接代码运行容器/执行池的服务。
			// 它返回标准输出、标准错误和异常信息，供上层统一处理。
			CodePoolExecutorService.TaskResponse taskResponse = this.codePoolExecutor.runTask(taskRequest);
			if (!taskResponse.isSuccess()) {
				String errorMsg = "Python Execute Failed!\nStdOut: " + taskResponse.stdOut() + "\nStdErr: "
						+ taskResponse.stdErr() + "\nExceptionMsg: " + taskResponse.exceptionMsg();
				log.error(errorMsg);

				// 达到最大重试次数后，不再继续回生成节点，而是进入降级模式，让后续分析节点给出兜底反馈。
				if (triesCount >= codeExecutorProperties.getPythonMaxTriesCount()) {
					log.error("Python 执行失败且已超过最大重试次数（已尝试次数：{}），启动降级兜底逻辑。错误信息: {}", triesCount,
							errorMsg);

					String fallbackOutput = "{}";

					Flux<ChatResponse> fallbackDisplayFlux = Flux.create(emitter -> {
						emitter.next(ChatResponseUtil.createResponse("开始执行 Python 代码..."));
						emitter.next(ChatResponseUtil.createResponse("Python 代码执行失败且已超过最大重试次数，采用降级策略继续处理。"));
						emitter.complete();
					});

					Flux<GraphResponse<StreamingOutput>> fallbackGenerator = FluxUtil
						.createStreamingGeneratorWithMessages(this.getClass(), state,
								v -> Map.of(PYTHON_EXECUTE_NODE_OUTPUT, fallbackOutput, PYTHON_IS_SUCCESS, false,
										PYTHON_FALLBACK_MODE, true),
								fallbackDisplayFlux);

					return Map.of(PYTHON_EXECUTE_NODE_OUTPUT, fallbackGenerator);
				}

				throw new RuntimeException(errorMsg);
			}

			// Python 代码 stdout 常常是 JSON。
			// 这里先尝试反序列化再重新序列化，目的是把 Unicode 转义等格式统一成更易读的输出。
			String stdout = taskResponse.stdOut();
			Object value = jsonParseUtil.tryConvertToObject(stdout, Object.class);
			if (value != null) {
				stdout = objectMapper.writeValueAsString(value);
			}
			String finalStdout = stdout;

			log.info("Python Execute Success! StdOut: {}", finalStdout);

			Flux<ChatResponse> displayFlux = Flux.create(emitter -> {
				emitter.next(ChatResponseUtil.createResponse("开始执行 Python 代码..."));
				emitter.next(ChatResponseUtil.createResponse("标准输出："));
				emitter.next(ChatResponseUtil.createPureResponse(TextType.JSON.getStartSign()));
				emitter.next(ChatResponseUtil.createResponse(finalStdout));
				emitter.next(ChatResponseUtil.createPureResponse(TextType.JSON.getEndSign()));
				emitter.next(ChatResponseUtil.createResponse("Python 代码执行成功。"));
				emitter.complete();
			});

			Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGeneratorWithMessages(
					this.getClass(), state,
					v -> Map.of(PYTHON_EXECUTE_NODE_OUTPUT, finalStdout, PYTHON_IS_SUCCESS, true), displayFlux);

			return Map.of(PYTHON_EXECUTE_NODE_OUTPUT, generator);
		}
		catch (Exception e) {
			String errorMessage = e.getMessage();
			log.error("Python Execute Exception: {}", errorMessage);

			Map<String, Object> errorResult = Map.of(PYTHON_EXECUTE_NODE_OUTPUT, errorMessage, PYTHON_IS_SUCCESS,
					false);

			Flux<ChatResponse> errorDisplayFlux = Flux.create(emitter -> {
				emitter.next(ChatResponseUtil.createResponse("开始执行 Python 代码..."));
				emitter.next(ChatResponseUtil.createResponse("Python 代码执行失败: " + errorMessage));
				emitter.complete();
			});

			var generator = FluxUtil.createStreamingGeneratorWithMessages(this.getClass(), state, v -> errorResult,
					errorDisplayFlux);

			return Map.of(PYTHON_EXECUTE_NODE_OUTPUT, generator);
		}
	}

}
