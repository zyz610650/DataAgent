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
package com.alibaba.cloud.ai.dataagent.service.knowledge;

import com.alibaba.cloud.ai.dataagent.vo.PageResult;
import com.alibaba.cloud.ai.dataagent.dto.knowledge.agentknowledge.AgentKnowledgeQueryDTO;
import com.alibaba.cloud.ai.dataagent.dto.knowledge.agentknowledge.CreateKnowledgeDTO;
import com.alibaba.cloud.ai.dataagent.dto.knowledge.agentknowledge.UpdateKnowledgeDTO;
import com.alibaba.cloud.ai.dataagent.vo.AgentKnowledgeVO;

/**
 * AgentKnowledgeService：服务层接口。
 *
 * 它定义了智能体知识相关能力的对外契约，让上层只依赖抽象，不直接绑定具体实现。
 * 先看接口可以快速建立能力全貌，再回实现类看细节。
 */
public interface AgentKnowledgeService {

	AgentKnowledgeVO getKnowledgeById(Integer id);

	AgentKnowledgeVO createKnowledge(CreateKnowledgeDTO createKnowledgeDto);

	AgentKnowledgeVO updateKnowledge(Integer id, UpdateKnowledgeDTO updateKnowledgeDto);

	boolean deleteKnowledge(Integer id);

	PageResult<AgentKnowledgeVO> queryByConditionsWithPage(AgentKnowledgeQueryDTO queryDTO);

	AgentKnowledgeVO updateKnowledgeRecallStatus(Integer id, Boolean recalled);

	void retryEmbedding(Integer id);

}
