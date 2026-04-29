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

import com.alibaba.cloud.ai.dataagent.entity.Agent;
import com.alibaba.cloud.ai.dataagent.service.agent.AgentService;
import com.alibaba.cloud.ai.dataagent.vo.ApiKeyResponse;
import com.alibaba.cloud.ai.dataagent.vo.ApiResponse;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * AgentController：HTTP 接口入口控制器。
 *
 * 它负责接收智能体相关请求、整理参数、调用下游 Service，并把结果包装成 REST 或 SSE 响应返回前端。
 * 学习时重点看接口地址、参数来源、参数校验以及最终委派到哪个 Service。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@CrossOrigin(origins = "*")
@AllArgsConstructor
public class AgentController {

	private final AgentService agentService;

	/**
 * `list`：读取当前场景所需的数据或状态。
 *
 * 这是接口入口方法，参数通常来自 HTTP 请求，返回值会直接影响前端收到的智能体相关结果。
 */
	@GetMapping("/list")
	public List<Agent> list(@RequestParam(value = "status", required = false) String status,
			@RequestParam(value = "keyword", required = false) String keyword) {
		List<Agent> result;
		if (StringUtils.isNotBlank(keyword)) {
			result = agentService.search(keyword);
		}
		else if (StringUtils.isNotBlank(status)) {
			result = agentService.findByStatus(status);
		}
		else {
			result = agentService.findAll();
		}
		return result;
	}

	/**
 * `get`：读取当前场景所需的数据或状态。
 *
 * 这是接口入口方法，参数通常来自 HTTP 请求，返回值会直接影响前端收到的智能体相关结果。
 */
	@GetMapping("/{id}")
	public Agent get(@PathVariable Long id) {
		return checkAgentExists(id);
	}

	/**
 * `create`：创建新的业务对象或新记录。
 *
 * 这是接口入口方法，参数通常来自 HTTP 请求，返回值会直接影响前端收到的智能体相关结果。
 */
	@PostMapping
	public Agent create(@RequestBody Agent agent) {
		// Set default status
		if (StringUtils.isBlank(agent.getStatus())) {
			agent.setStatus("draft");
		}
		return agentService.save(agent);
	}

	/**
 * `update`：更新已有对象的字段、状态或开关配置。
 *
 * 这是接口入口方法，参数通常来自 HTTP 请求，返回值会直接影响前端收到的智能体相关结果。
 */
	@PutMapping("/{id}")
	public Agent update(@PathVariable Long id, @RequestBody Agent agent) {
		checkAgentExists(id);
		agent.setId(id);
		return agentService.save(agent);
	}

	/**
 * `delete`：删除对象、解绑关系，或清理不再需要的数据。
 *
 * 这是接口入口方法，参数通常来自 HTTP 请求，返回值会直接影响前端收到的智能体相关结果。
 */
	@DeleteMapping("/{id}")
	public void delete(@PathVariable Long id) {
		checkAgentExists(id);
		agentService.deleteById(id);
	}

	/**
 * `publish`：向外发布事件、消息或流式结果。
 *
 * 这是接口入口方法，参数通常来自 HTTP 请求，返回值会直接影响前端收到的智能体相关结果。
 */
	@PostMapping("/{id}/publish")
	public Agent publish(@PathVariable Long id) {
		Agent agent = checkAgentExists(id);
		agent.setStatus("published");
		return agentService.save(agent);
	}

	/**
 * `offline`：执行当前类对外暴露的一步核心操作。
 *
 * 这是接口入口方法，参数通常来自 HTTP 请求，返回值会直接影响前端收到的智能体相关结果。
 */
	@PostMapping("/{id}/offline")
	public Agent offline(@PathVariable Long id) {
		Agent agent = checkAgentExists(id);
		agent.setStatus("offline");
		return agentService.save(agent);
	}

	/**
 * `getApiKey`：读取当前场景所需的数据或状态。
 *
 * 这是接口入口方法，参数通常来自 HTTP 请求，返回值会直接影响前端收到的智能体相关结果。
 */
	@GetMapping("/{id}/api-key")
	public ApiResponse<ApiKeyResponse> getApiKey(@PathVariable Long id) {
		Agent agent = checkAgentExists(id);
		String masked = agentService.getApiKeyMasked(id);
		return buildApiKeyResponse(masked, agent.getApiKeyEnabled(), "获取 API Key 成功");
	}

	/**
 * `generateApiKey`：生成、重写或召回当前阶段需要的内容。
 *
 * 这是接口入口方法，参数通常来自 HTTP 请求，返回值会直接影响前端收到的智能体相关结果。
 */
	@PostMapping("/{id}/api-key/generate")
	public ApiResponse<ApiKeyResponse> generateApiKey(@PathVariable Long id) {
		checkAgentExists(id);
		Agent agent = agentService.generateApiKey(id);
		return buildApiKeyResponse(agent.getApiKey(), agent.getApiKeyEnabled(), "生成 API Key 成功");
	}

	/**
 * `resetApiKey`：执行当前类对外暴露的一步核心操作。
 *
 * 这是接口入口方法，参数通常来自 HTTP 请求，返回值会直接影响前端收到的智能体相关结果。
 */
	@PostMapping("/{id}/api-key/reset")
	public ApiResponse<ApiKeyResponse> resetApiKey(@PathVariable Long id) {
		checkAgentExists(id);
		Agent agent = agentService.resetApiKey(id);
		return buildApiKeyResponse(agent.getApiKey(), agent.getApiKeyEnabled(), "重置 API Key 成功");
	}

	/**
 * `deleteApiKey`：删除对象、解绑关系，或清理不再需要的数据。
 *
 * 这是接口入口方法，参数通常来自 HTTP 请求，返回值会直接影响前端收到的智能体相关结果。
 */
	@DeleteMapping("/{id}/api-key")
	public ApiResponse<ApiKeyResponse> deleteApiKey(@PathVariable Long id) {
		checkAgentExists(id);
		Agent agent = agentService.deleteApiKey(id);
		return buildApiKeyResponse(agent.getApiKey(), agent.getApiKeyEnabled(), "删除 API Key 成功");
	}

	/**
 * `toggleApiKey`：更新已有对象的字段、状态或开关配置。
 *
 * 这是接口入口方法，参数通常来自 HTTP 请求，返回值会直接影响前端收到的智能体相关结果。
 */
	@PostMapping("/{id}/api-key/enable")
	public ApiResponse<ApiKeyResponse> toggleApiKey(@PathVariable Long id, @RequestParam("enabled") boolean enabled) {
		checkAgentExists(id);
		Agent agent = agentService.toggleApiKey(id, enabled);
		return buildApiKeyResponse(agent.getApiKey() == null ? null : "****", agent.getApiKeyEnabled(),
				"更新 API Key 状态成功");
	}

	private Agent checkAgentExists(Long id) {
		Agent agent = agentService.findById(id);
		if (agent == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "agent with id: %d not found".formatted(id));
		}
		return agent;
	}

	private ApiResponse<ApiKeyResponse> buildApiKeyResponse(String apiKey, Integer apiKeyEnabled, String message) {
		return ApiResponse.success(message, new ApiKeyResponse(apiKey, apiKeyEnabled));
	}

}
