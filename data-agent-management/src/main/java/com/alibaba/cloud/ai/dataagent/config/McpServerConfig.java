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
package com.alibaba.cloud.ai.dataagent.config;

import com.alibaba.cloud.ai.dataagent.annotation.McpServerTool;
import com.alibaba.cloud.ai.dataagent.service.mcp.McpServerService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP Server 工具装配配置。
 *
 * 这个类负责把 `McpServerService` 暴露为 Spring AI 可识别的工具集合。
 * 之所以单独拆一个配置类，是因为这里牵涉到“工具扫描时机”和“模型初始化时机”的顺序问题。
 *
 * 关键框架 API：
 * - {@link ToolCallbackProvider}：
 *   Spring AI 的工具提供者抽象，模型在推理过程中如果要调用外部工具，最终会落到这里暴露的回调。
 * - {@link MethodToolCallbackProvider}：
 *   它会扫描传入对象的方法，并把它们包装成可被大模型调用的工具定义。
 *
 * 设计要点：
 * - 本项目用自定义注解 `@McpServerTool` 标记这类工具 Bean。
 * - 这样可以避免某些 ChatModel Starter 在初始化阶段过早扫描工具，从而引入循环依赖。
 */
@Configuration
public class McpServerConfig {

	/**
	 * 把 `McpServerService` 的公开方法注册为工具回调。
	 *
	 * 为什么要这样做：
	 * 1. MCP 暴露给外部调用的能力，本质上还是普通 Java 方法。
	 * 2. `MethodToolCallbackProvider` 会把这些方法转换成 LLM 可理解的 Tool 定义。
	 * 3. Graph 或 Chat 流程中如果触发工具调用，Spring AI 就能从解析链中找到这些工具。
	 *
	 * 对阅读者来说，这个 Bean 可以理解为“Java 服务方法 -> 大模型工具协议”的桥接器。
	 */
	@Bean
	@McpServerTool
	public ToolCallbackProvider mcpServerTools(McpServerService mcpServerService) {
		return MethodToolCallbackProvider.builder().toolObjects(mcpServerService).build();
	}

}
