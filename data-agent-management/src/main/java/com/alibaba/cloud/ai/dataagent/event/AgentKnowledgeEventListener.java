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
package com.alibaba.cloud.ai.dataagent.event;

import com.alibaba.cloud.ai.dataagent.entity.AgentKnowledge;
import com.alibaba.cloud.ai.dataagent.enums.EmbeddingStatus;
import com.alibaba.cloud.ai.dataagent.mapper.AgentKnowledgeMapper;
import com.alibaba.cloud.ai.dataagent.service.knowledge.AgentKnowledgeResourceManager;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 智能体知识事件监听器。
 *
 * 它连接了“知识记录写入数据库”和“异步资源处理”这两条链路：
 * - 创建知识后，监听 embedding 事件并写入向量库
 * - 删除知识后，监听 deletion 事件并清理文件与向量数据
 *
 * 这里使用的是“事务提交后异步监听”模式：
 * - `TransactionPhase.AFTER_COMMIT` 确保只有数据库主事务成功提交后才会触发
 * - `@Async("dbOperationExecutor")` 把重活放到专用线程池里，不阻塞接口返回
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AgentKnowledgeEventListener {

	private final AgentKnowledgeMapper agentKnowledgeMapper;

	private final AgentKnowledgeResourceManager agentKnowledgeResourceManager;

	/**
	 * 处理知识 embedding 事件。
	 *
	 * 执行步骤：
	 * 1. 重新从数据库读取最新知识记录
	 * 2. 把状态更新成 PROCESSING
	 * 3. 执行向量化并写入向量库
	 * 4. 成功则标记 COMPLETED，失败则标记 FAILED
	 *
	 * 这里重新查库而不是直接用事件里塞完整对象，是为了降低事件载荷，并保证拿到的是提交后的最终数据。
	 */
	@Async("dbOperationExecutor")
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void handleEmbeddingEvent(AgentKnowledgeEmbeddingEvent event) {
		log.info("Received AgentKnowledgeEmbeddingEvent. agentKnowledgeId: {}", event.getKnowledgeId());
		Integer id = event.getKnowledgeId();

		AgentKnowledge knowledge = agentKnowledgeMapper.selectById(id);
		if (knowledge == null) {
			log.error("Knowledge not found during async processing. Id: {}", id);
			return;
		}

		try {
			updateStatus(knowledge, EmbeddingStatus.PROCESSING, null);
			agentKnowledgeResourceManager.doEmbedingToVectorStore(knowledge);
			updateStatus(knowledge, EmbeddingStatus.COMPLETED, null);

			log.info("Successfully embedded knowledge. Id: {}", id);
		}
		catch (Exception e) {
			log.error("Failed to embed knowledge. Id: {}", id, e);
			updateStatus(knowledge, EmbeddingStatus.FAILED, e.getMessage());
		}
		log.info("Finished processing AgentKnowledgeEmbeddingEvent. agentKnowledgeId: {}", event.getKnowledgeId());

	}

	/**
	 * 更新 embedding 状态。
	 *
	 * 这里单独拆出来，是为了统一处理更新时间和错误信息截断逻辑。
	 */
	private void updateStatus(AgentKnowledge knowledge, EmbeddingStatus status, String errorMsg) {
		knowledge.setEmbeddingStatus(status);
		knowledge.setUpdatedTime(LocalDateTime.now());
		if (errorMsg != null) {
			// 错误信息可能非常长，这里做数据库安全截断。
			knowledge.setErrorMsg(errorMsg.length() > 250 ? errorMsg.substring(0, 250) : errorMsg);
		}
		agentKnowledgeMapper.update(knowledge);
	}

	/**
	 * 处理知识删除后的异步资源清理。
	 *
	 * 清理内容包括：
	 * 1. 向量库中的嵌入数据
	 * 2. 文件存储中的原始知识文件
	 *
	 * 只有两者都成功时，才把 `isResourceCleaned` 标记为 1。
	 * 如果某一步失败，后续可以依赖定时任务或人工补偿继续清理。
	 */
	@Async("dbOperationExecutor")
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void handleDeletionEvent(AgentKnowledgeDeletionEvent event) {
		Integer id = event.getKnowledgeId();
		log.info("Starting async resource cleanup for knowledgeId: {}", id);

		AgentKnowledge knowledge = agentKnowledgeMapper.selectByIdIncludeDeleted(id);
		if (knowledge == null) {
			log.warn("Knowledge record physically missing, skipping cleanup. ID: {}", id);
			return;
		}

		try {
			boolean vectorDeleted = agentKnowledgeResourceManager.deleteFromVectorStore(knowledge.getAgentId(), id);
			boolean fileDeleted = agentKnowledgeResourceManager.deleteKnowledgeFile(knowledge);

			if (vectorDeleted && fileDeleted) {
				knowledge.setIsResourceCleaned(1);
				knowledge.setUpdatedTime(LocalDateTime.now());
				agentKnowledgeMapper.update(knowledge);
				log.info("Resources cleaned up successfully. AgentKnowledgeID: {}", id);
			}
			else {
				log.error("Cleanup incomplete. AgentKnowledgeID: {}, VectorDeleted: {}, FileDeleted: {}", id,
						vectorDeleted, fileDeleted);
			}

		}
		catch (Exception e) {
			log.error("Exception during async cleanup for agentKnowledgeId: {}", id, e);
		}
	}

}
