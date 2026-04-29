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
package com.alibaba.cloud.ai.dataagent.controller;

import com.alibaba.cloud.ai.dataagent.dto.GraphRequest;
import com.alibaba.cloud.ai.dataagent.service.graph.GraphService;
import com.alibaba.cloud.ai.dataagent.vo.GraphNodeResponse;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.STREAM_EVENT_COMPLETE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.STREAM_EVENT_ERROR;

/**
 * Graph 流式执行入口 Controller。
 *
 * 这个类承接前端“开始分析”请求，并把它交给 `GraphService` 驱动整个 StateGraph 主链路。
 * 它本身不负责 NL2SQL、Python、RAG 等具体逻辑，只做协议层转换和 SSE 生命周期管理。
 *
 * 为什么这里使用 WebFlux + SSE：
 * 1. 大模型和多节点编排会持续产生中间结果，适合边算边推。
 * 2. `text/event-stream` 可以让浏览器长连接接收增量事件。
 * 3. 用户能实时看到计划、SQL、执行结果，而不是等待整条链路结束。
 *
 * 关键框架 API：
 * - `Flux<T>`：Reactor 的响应式流，表示 0 到 N 个异步元素。
 * - `ServerSentEvent<T>`：SSE 事件包装器，允许携带 `event`、`data` 等字段。
 * - `Sinks.Many<T>`：手动向响应式流中推送数据的入口，这里用它把 Graph 输出桥接到 HTTP 流。
 */
@Slf4j
@RestController
@AllArgsConstructor
@CrossOrigin(origins = "*")
@RequestMapping("/api")
public class GraphController {

	private final GraphService graphService;

	/**
	 * 发起一次 SSE 流式搜索/分析请求。
	 *
	 * 参数说明：
	 * - `agentId`：当前问题归属的智能体，决定模型、数据源、知识库等上下文。
	 * - `threadId`：当前流式任务或多轮会话的唯一标识；为空时后端会自动生成。
	 * - `query`：用户输入的问题。
	 * - `humanFeedback`：是否启用人工反馈模式。
	 * - `humanFeedbackContent`：用户对上一轮计划给出的反馈内容。
	 * - `rejectedPlan`：是否表示用户拒绝了上一轮计划。
	 * - `nl2sqlOnly`：是否只执行 NL2SQL，不进入 Python 和报告生成链路。
	 *
	 * 学习要点：
	 * 1. Controller 只负责组装 `GraphRequest`，不承担复杂业务判断。
	 * 2. 真正的图执行和上下文管理在 `GraphServiceImpl` 中完成。
	 * 3. `doOnCancel/doOnError/doOnComplete` 用于绑定连接生命周期与后台任务清理。
	 */
	@GetMapping(value = "/stream/search", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<GraphNodeResponse>> streamSearch(@RequestParam("agentId") String agentId,
			@RequestParam(value = "threadId", required = false) String threadId, @RequestParam("query") String query,
			@RequestParam(value = "humanFeedback", required = false) boolean humanFeedback,
			@RequestParam(value = "humanFeedbackContent", required = false) String humanFeedbackContent,
			@RequestParam(value = "rejectedPlan", required = false) boolean rejectedPlan,
			@RequestParam(value = "nl2sqlOnly", required = false) boolean nl2sqlOnly, ServerHttpResponse response) {
		// SSE 长连接一般要关闭缓存，并显式保持连接，避免中间代理或浏览器错误缓存事件。
		response.getHeaders().add("Cache-Control", "no-cache");
		response.getHeaders().add("Connection", "keep-alive");
		response.getHeaders().add("Access-Control-Allow-Origin", "*");

		// `unicast` 表示一个请求只会有一个订阅者，适合“一个浏览器连接对应一个流任务”的场景。
		// `onBackpressureBuffer` 表示前端消费稍慢时先做缓冲，而不是立刻丢事件。
		Sinks.Many<ServerSentEvent<GraphNodeResponse>> sink = Sinks.many().unicast().onBackpressureBuffer();

		GraphRequest request = GraphRequest.builder()
			.agentId(agentId)
			.threadId(threadId)
			.query(query)
			.humanFeedback(humanFeedback)
			.humanFeedbackContent(humanFeedbackContent)
			.rejectedPlan(rejectedPlan)
			.nl2sqlOnly(nl2sqlOnly)
			.build();

		// 真正的工作流执行、恢复和取消都在 GraphService 内完成。
		graphService.graphStreamProcess(sink, request);

		return sink.asFlux().filter(sse -> {
			// `complete` 和 `error` 是前端判断任务结束状态的关键事件，必须无条件透传。
			if (STREAM_EVENT_COMPLETE.equals(sse.event()) || STREAM_EVENT_ERROR.equals(sse.event())) {
				return true;
			}
			// 普通数据事件如果没有文本内容，就不必再推给前端，避免出现空白 token。
			return sse.data() != null && sse.data().getText() != null && !sse.data().getText().isEmpty();
		})
			.doOnSubscribe(subscription -> log.info("Client subscribed to stream, threadId: {}", request.getThreadId()))
			.doOnCancel(() -> {
				log.info("Client disconnected from stream, threadId: {}", request.getThreadId());
				// 浏览器关闭页面、断网或主动取消请求时，这里会收到取消信号。
				// 后端必须同步停止图执行，避免继续占用模型、数据库和线程资源。
				if (request.getThreadId() != null) {
					graphService.stopStreamProcessing(request.getThreadId());
				}
			})
			.doOnError(e -> {
				log.error("Error occurred during streaming, threadId: {}: ", request.getThreadId(), e);
				// 这里主要做兜底清理，真正的错误事件由 GraphService 中的流处理逻辑负责发送。
				if (request.getThreadId() != null) {
					graphService.stopStreamProcessing(request.getThreadId());
				}
			})
			.doOnComplete(() -> log.info("Stream completed successfully, threadId: {}", request.getThreadId()));
	}

}
