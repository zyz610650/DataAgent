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

import com.alibaba.cloud.ai.dataagent.dto.prompt.IntentRecognitionOutputDTO;
import com.alibaba.cloud.ai.dataagent.enums.TextType;
import com.alibaba.cloud.ai.dataagent.prompt.PromptHelper;
import com.alibaba.cloud.ai.dataagent.service.llm.LlmService;
import com.alibaba.cloud.ai.dataagent.util.ChatResponseUtil;
import com.alibaba.cloud.ai.dataagent.util.FluxUtil;
import com.alibaba.cloud.ai.dataagent.util.JsonParseUtil;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.INPUT_KEY;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.INTENT_RECOGNITION_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.MULTI_TURN_CONTEXT;

/**
 * 意图识别节点。
 *
 * 这是整条数据分析工作流里最靠前的“问题理解”节点，目标不是立刻回答用户，而是先判定：
 * 1. 当前输入是不是一个值得继续进入分析链路的问题。
 * 2. 这个输入更像闲聊、无关指令，还是明确的数据分析请求。
 *
 * 之所以要把这一步单独拆出来，是因为后续节点会触发证据召回、Schema 召回、SQL 生成等高成本操作。
 * 如果一开始就能判断“这不是分析请求”，系统就可以尽早结束流程，减少模型调用和数据库侧开销。
 *
 * 框架 API 说明：
 * - `NodeAction` 是 Graph 框架中的节点执行接口。图运行到当前节点时，会调用 `apply(...)`。
 * - `OverAllState` 是整条工作流共享的状态容器，上游节点写入、下游节点读取。
 * - `Flux<ChatResponse>` 是 Reactor 的响应式流，这里用来承接大模型的流式输出，使前端可以实时看到处理进度。
 */
@Slf4j
@Component
@AllArgsConstructor
public class IntentRecognitionNode implements NodeAction {

	private final LlmService llmService;

	private final JsonParseUtil jsonParseUtil;

	/**
	 * 执行意图识别。
	 *
	 * 返回值不是直接的 DTO，而是一个“流式生成器”：
	 * - 前端可以先收到“正在识别意图”的过程消息。
	 * - 当模型流式输出结束后，回调会把最终文本解析成 `IntentRecognitionOutputDTO` 并写回状态。
	 *
	 * 这种设计把“交互式展示”和“结构化状态回写”统一到了一个封装里，便于整个 Graph 复用同一种运行模式。
	 */
	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		// 从全局状态里拿到用户原始输入。这个值通常在工作流入口处就已经放入 state。
		String userInput = StateUtil.getStringValue(state, INPUT_KEY);
		log.info("User input for intent recognition: {}", userInput);

		// 多轮上下文为空时给一个默认占位，避免 Prompt 模板渲染出 null。
		String multiTurn = StateUtil.getStringValue(state, MULTI_TURN_CONTEXT, "(无)");

		// PromptHelper 负责统一管理提示词拼装逻辑，节点本身只关心输入和输出，不散落模板细节。
		String prompt = PromptHelper.buildIntentRecognitionPrompt(multiTurn, userInput);
		log.debug("Built intent recognition prompt as follows \n {} \n", prompt);

		// `callUser` 会调用底层大模型，并返回 Spring AI 统一抽象的 `ChatResponse` 流。
		Flux<ChatResponse> responseFlux = llmService.callUser(prompt);

		// `createStreamingGenerator` 负责两件事：
		// 1. 把模型返回的流包装为 Graph 可消费的输出流。
		// 2. 在流结束后执行结果回调，将文本解析为结构化 DTO 并写回 state。
		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGenerator(this.getClass(), state,
				responseFlux,
				Flux.just(ChatResponseUtil.createResponse("正在进行意图识别..."),
						ChatResponseUtil.createPureResponse(TextType.JSON.getStartSign())),
				Flux.just(ChatResponseUtil.createPureResponse(TextType.JSON.getEndSign()),
						ChatResponseUtil.createResponse("\n意图识别完成。")),
				result -> {
					// 这里依赖提示词协议，要求模型输出 JSON。
					// `JsonParseUtil` 用于把模型输出尽量稳健地反序列化为目标 DTO。
					IntentRecognitionOutputDTO intentRecognitionOutput = jsonParseUtil.tryConvertToObject(result,
							IntentRecognitionOutputDTO.class);
					return Map.of(INTENT_RECOGNITION_NODE_OUTPUT, intentRecognitionOutput);
				});

		// Graph 约定：节点返回的 Map key 表示“本节点对外暴露的状态名”。
		return Map.of(INTENT_RECOGNITION_NODE_OUTPUT, generator);
	}

}
