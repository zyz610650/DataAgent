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
package com.alibaba.cloud.ai.dataagent.service.agent;

import com.alibaba.cloud.ai.dataagent.entity.Agent;

import java.util.List;

/**
 * AgentService：服务层接口。
 *
 * 它定义了智能体相关能力的对外契约，让上层只依赖抽象，不直接绑定具体实现。
 * 先看接口可以快速建立能力全貌，再回实现类看细节。
 */
public interface AgentService {

	List<Agent> findAll();

	Agent findById(Long id);

	List<Agent> findByStatus(String status);

	List<Agent> search(String keyword);

	Agent save(Agent agent);

	void deleteById(Long id);

	Agent generateApiKey(Long id);

	Agent resetApiKey(Long id);

	Agent deleteApiKey(Long id);

	Agent toggleApiKey(Long id, boolean enabled);

	String getApiKeyMasked(Long id);

}
