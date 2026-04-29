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

import com.alibaba.cloud.ai.dataagent.converter.AgentKnowledgeConverter;
import com.alibaba.cloud.ai.dataagent.dto.knowledge.agentknowledge.AgentKnowledgeQueryDTO;
import com.alibaba.cloud.ai.dataagent.dto.knowledge.agentknowledge.CreateKnowledgeDTO;
import com.alibaba.cloud.ai.dataagent.dto.knowledge.agentknowledge.UpdateKnowledgeDTO;
import com.alibaba.cloud.ai.dataagent.entity.AgentKnowledge;
import com.alibaba.cloud.ai.dataagent.enums.EmbeddingStatus;
import com.alibaba.cloud.ai.dataagent.enums.KnowledgeType;
import com.alibaba.cloud.ai.dataagent.event.AgentKnowledgeDeletionEvent;
import com.alibaba.cloud.ai.dataagent.event.AgentKnowledgeEmbeddingEvent;
import com.alibaba.cloud.ai.dataagent.mapper.AgentKnowledgeMapper;
import com.alibaba.cloud.ai.dataagent.service.file.FileStorageService;
import com.alibaba.cloud.ai.dataagent.vo.AgentKnowledgeVO;
import com.alibaba.cloud.ai.dataagent.vo.PageResult;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 智能体知识库服务实现。
 *
 * 这个服务管理的是“挂在某个 Agent 下的知识资源”，包括：
 * - 文档型知识
 * - QA / FAQ 型知识
 *
 * 核心职责：
 * 1. 管理知识记录本身的增删改查
 * 2. 在文档知识创建时触发文件落盘
 * 3. 通过 Spring Event 驱动异步嵌入、异步资源清理
 *
 * 这里非常值得注意的一点是：知识写库和向量化不是强同步流程。
 * 服务层只负责“创建记录并发事件”，真正的 embedding 在事件监听链路里继续执行。
 */
@Slf4j
@Service
@AllArgsConstructor
public class AgentKnowledgeServiceImpl implements AgentKnowledgeService {

	private static final String AGENT_KNOWLEDGE_FILE_PATH = "agent-knowledge";

	private final AgentKnowledgeMapper agentKnowledgeMapper;

	private final FileStorageService fileStorageService;

	private final AgentKnowledgeConverter agentKnowledgeConverter;

	private final ApplicationEventPublisher eventPublisher;

	/**
 * `getKnowledgeById`：按主键或唯一标识查询单个对象。
 *
 * 它处在服务层，常见上游是 Controller、Workflow 节点或事件监听器，下游则可能是 Mapper、模型服务或外部组件。
 */
	@Override
	public AgentKnowledgeVO getKnowledgeById(Integer id) {
		AgentKnowledge agentKnowledge = agentKnowledgeMapper.selectById(id);
		return agentKnowledge == null ? null : agentKnowledgeConverter.toVo(agentKnowledge);
	}

	/**
	 * 创建知识。
	 *
	 * 处理顺序：
	 * 1. 参数校验
	 * 2. 如果是文档知识，则先把文件落到存储层
	 * 3. 写入数据库
	 * 4. 发布嵌入事件，交给后续异步流程处理
	 *
	 * `@Transactional` 的意义：
	 * - 保证数据库层面的创建流程要么整体成功，要么整体回滚。
	 * - 但文件系统和事件监听不属于同一个事务边界，所以这里仍然需要业务上允许最终一致性。
	 */
	@Override
	@Transactional
	public AgentKnowledgeVO createKnowledge(CreateKnowledgeDTO createKnowledgeDto) {
		String storagePath = null;
		checkCreateKnowledgeDto(createKnowledgeDto);

		if (createKnowledgeDto.getType().equals(KnowledgeType.DOCUMENT.getCode())) {
			try {
				storagePath = fileStorageService.storeFile(createKnowledgeDto.getFile(), AGENT_KNOWLEDGE_FILE_PATH);
			}
			catch (Exception e) {
				log.error("Failed to store file, agentId:{} title:{} type:{} ", createKnowledgeDto.getAgentId(),
						createKnowledgeDto.getTitle(), createKnowledgeDto.getType());
				throw new RuntimeException("Failed to store file.");
			}
		}

		AgentKnowledge knowledge = agentKnowledgeConverter.toEntityForCreate(createKnowledgeDto, storagePath);

		if (agentKnowledgeMapper.insert(knowledge) <= 0) {
			log.error("Failed to create knowledge, agentId:{} title:{} type:{} ", knowledge.getAgentId(),
					knowledge.getTitle(), knowledge.getType());
			throw new RuntimeException("Failed to create knowledge in database.");
		}

		eventPublisher
			.publishEvent(new AgentKnowledgeEmbeddingEvent(this, knowledge.getId(), knowledge.getSplitterType()));
		log.info("Knowledge created and event published. Id: {}, splitterType: {}", knowledge.getId(),
				knowledge.getSplitterType());

		return agentKnowledgeConverter.toVo(knowledge);
	}

	/**
	 * 创建前的基础校验。
	 *
	 * 这里按知识类型做最小必要字段约束：
	 * - 文档知识必须带文件
	 * - QA / FAQ 必须同时带 question 和 content
	 */
	private static void checkCreateKnowledgeDto(CreateKnowledgeDTO createKnowledgeDto) {
		if (createKnowledgeDto.getType().equals(KnowledgeType.DOCUMENT.getCode())
				&& createKnowledgeDto.getFile() == null) {
			throw new RuntimeException("File is required for document type.");
		}
		if (createKnowledgeDto.getType().equals(KnowledgeType.QA.getCode())
				|| createKnowledgeDto.getType().equals(KnowledgeType.FAQ.getCode())) {

			if (!StringUtils.hasText(createKnowledgeDto.getQuestion())) {
				throw new RuntimeException("Question is required for QA or FAQ type.");
			}
			if (!StringUtils.hasText(createKnowledgeDto.getContent())) {
				throw new RuntimeException("Content is required for QA or FAQ type.");
			}
		}
	}

	/**
	 * 更新知识的基础文本信息。
	 *
	 * 当前更新逻辑比较保守，只允许更新标题和内容，不在这里处理文件替换。
	 */
	@Override
	@Transactional
	public AgentKnowledgeVO updateKnowledge(Integer id, UpdateKnowledgeDTO updateKnowledgeDto) {
		AgentKnowledge existingKnowledge = agentKnowledgeMapper.selectById(id);
		if (existingKnowledge == null) {
			log.warn("Knowledge not found with id: {}", id);
			throw new RuntimeException("Knowledge not found.");
		}

		if (StringUtils.hasText(updateKnowledgeDto.getTitle())) {
			existingKnowledge.setTitle(updateKnowledgeDto.getTitle());
		}

		if (StringUtils.hasText(updateKnowledgeDto.getContent())) {
			existingKnowledge.setContent(updateKnowledgeDto.getContent());
		}

		int updateResult = agentKnowledgeMapper.update(existingKnowledge);
		if (updateResult <= 0) {
			log.error("Failed to update knowledge with id: {}", existingKnowledge.getId());
			throw new RuntimeException("Failed to update knowledge in database.");
		}
		return agentKnowledgeConverter.toVo(existingKnowledge);
	}

	/**
	 * 删除知识。
	 *
	 * 这里不是物理删除，而是软删除：
	 * 1. 先把数据库记录标记为已删除
	 * 2. 再发布删除事件，让后续清理线程去处理文件和向量数据
	 *
	 * 这样做的好处是：
	 * - 接口响应更快
	 * - 失败时更容易补偿
	 * - 能减少“删数据库成功但删文件/删向量失败”时的中间不一致窗口
	 */
	@Override
	@Transactional
	public boolean deleteKnowledge(Integer id) {
		AgentKnowledge knowledge = agentKnowledgeMapper.selectById(id);
		if (knowledge == null) {
			log.warn("Knowledge not found with id: {}, treating as already deleted", id);
			return true;
		}

		knowledge.setIsDeleted(1);
		knowledge.setIsResourceCleaned(0);
		knowledge.setUpdatedTime(LocalDateTime.now());

		if (agentKnowledgeMapper.update(knowledge) > 0) {
			eventPublisher.publishEvent(new AgentKnowledgeDeletionEvent(this, id));
			return true;
		}
		return false;
	}

	/**
 * `queryByConditionsWithPage`：按指定条件查询对象或结果列表。
 *
 * 它处在服务层，常见上游是 Controller、Workflow 节点或事件监听器，下游则可能是 Mapper、模型服务或外部组件。
 */
	@Override
	public PageResult<AgentKnowledgeVO> queryByConditionsWithPage(AgentKnowledgeQueryDTO queryDTO) {
		int offset = (queryDTO.getPageNum() - 1) * queryDTO.getPageSize();

		Long total = agentKnowledgeMapper.countByConditions(queryDTO);

		List<AgentKnowledge> dataList = agentKnowledgeMapper.selectByConditionsWithPage(queryDTO, offset);
		List<AgentKnowledgeVO> dataListVO = dataList.stream().map(agentKnowledgeConverter::toVo).toList();
		PageResult<AgentKnowledgeVO> pageResult = new PageResult<>();
		pageResult.setData(dataListVO);
		pageResult.setTotal(total);
		pageResult.setPageNum(queryDTO.getPageNum());
		pageResult.setPageSize(queryDTO.getPageSize());
		pageResult.calculateTotalPages();

		return pageResult;
	}

	/**
	 * 更新知识是否参与召回。
	 *
	 * 这里的 recalled 状态本质上是“是否允许该知识参与 RAG 检索”的业务开关。
	 */
	@Override
	public AgentKnowledgeVO updateKnowledgeRecallStatus(Integer id, Boolean recalled) {
		AgentKnowledge knowledge = agentKnowledgeMapper.selectById(id);
		if (knowledge == null) {
			throw new RuntimeException("Knowledge not found.");
		}

		knowledge.setIsRecall(recalled ? 1 : 0);

		boolean res = agentKnowledgeMapper.update(knowledge) > 0;
		if (!res) {
			log.error("Failed to update knowledge with id: {}", knowledge.getId());
			throw new RuntimeException("Failed to update knowledge in database.");
		}
		return agentKnowledgeConverter.toVo(knowledge);
	}

	/**
	 * 手动重试 embedding。
	 *
	 * 使用场景：
	 * - 上一次向量化失败
	 * - 模型或向量库故障恢复后，想重新发起嵌入
	 *
	 * 前置约束：
	 * - 不能在 processing 中重复发起
	 * - 必须是允许召回的知识，否则重试没有业务意义
	 */
	@Override
	@Transactional
	public void retryEmbedding(Integer id) {
		AgentKnowledge knowledge = agentKnowledgeMapper.selectById(id);
		if (knowledge.getEmbeddingStatus().equals(EmbeddingStatus.PROCESSING)) {
			throw new RuntimeException("BusinessKnowledge is processing, please wait.");
		}

		if (knowledge.getIsRecall() == null || knowledge.getIsRecall() == 0) {
			throw new RuntimeException("BusinessKnowledge is not recalled, please recall it first.");
		}

		knowledge.setEmbeddingStatus(EmbeddingStatus.PENDING);
		knowledge.setErrorMsg("");
		agentKnowledgeMapper.update(knowledge);
		eventPublisher
			.publishEvent(new AgentKnowledgeEmbeddingEvent(this, knowledge.getId(), knowledge.getSplitterType()));
		log.info("Retry embedding for knowledgeId: {}, splitterType: {}", id, knowledge.getSplitterType());
	}

}
