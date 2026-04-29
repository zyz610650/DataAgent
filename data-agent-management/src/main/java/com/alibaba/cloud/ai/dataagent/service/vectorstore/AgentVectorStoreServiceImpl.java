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
package com.alibaba.cloud.ai.dataagent.service.vectorstore;

import com.alibaba.cloud.ai.dataagent.constant.Constant;
import com.alibaba.cloud.ai.dataagent.constant.DocumentMetadataConstant;
import com.alibaba.cloud.ai.dataagent.dto.search.AgentSearchRequest;
import com.alibaba.cloud.ai.dataagent.dto.search.HybridSearchRequest;
import com.alibaba.cloud.ai.dataagent.properties.DataAgentProperties;
import com.alibaba.cloud.ai.dataagent.service.hybrid.retrieval.HybridRetrievalStrategy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import static com.alibaba.cloud.ai.dataagent.service.vectorstore.DynamicFilterService.buildFilterExpressionString;

/**
 * 智能体向量库服务实现。
 *
 * 这是业务层和 Spring AI `VectorStore` 抽象之间的核心适配层，负责：
 * 1. 根据 agentId、文档类型等业务条件组装过滤表达式
 * 2. 决定走纯向量检索还是混合检索
 * 3. 批量添加和删除向量文档
 * 4. 屏蔽不同 VectorStore 实现之间的行为差异
 *
 * 关键框架 API：
 * - `VectorStore`：
 *   Spring AI 对向量数据库的统一抽象。
 * - `SearchRequest`：
 *   检索请求对象，封装 query、topK、similarityThreshold、filterExpression 等参数。
 * - `Filter.Expression`：
 *   元数据过滤条件表达式，用来把“只搜某个 Agent 的某类文档”这种业务条件下推到向量库。
 */
@Slf4j
@Service
public class AgentVectorStoreServiceImpl implements AgentVectorStoreService {

	/**
	 * 某些 VectorStore 实现不支持空 query，因此这里统一使用一个占位默认词。
	 * 当我们只想按 filter 删文档或查文档时，就用它来满足底层接口要求。
	 */
	private static final String DEFAULT = "default";

	private final VectorStore vectorStore;

	private final Optional<HybridRetrievalStrategy> hybridRetrievalStrategy;

	private final DataAgentProperties dataAgentProperties;

	private final DynamicFilterService dynamicFilterService;

	public AgentVectorStoreServiceImpl(VectorStore vectorStore,
			Optional<HybridRetrievalStrategy> hybridRetrievalStrategy, DataAgentProperties dataAgentProperties,
			DynamicFilterService dynamicFilterService) {
		this.vectorStore = vectorStore;
		this.hybridRetrievalStrategy = hybridRetrievalStrategy;
		this.dataAgentProperties = dataAgentProperties;
		this.dynamicFilterService = dynamicFilterService;
		log.info("VectorStore type: {}", vectorStore.getClass().getSimpleName());
	}

	/**
	 * 按 Agent 维度检索文档。
	 *
	 * 核心流程：
	 * 1. 根据 agentId 和 docVectorType 生成动态过滤条件。
	 * 2. 如果开启混合检索且策略存在，则走 hybrid retrieval。
	 * 3. 否则回退到纯向量检索。
	 */
	@Override
	public List<Document> search(AgentSearchRequest searchRequest) {
		Assert.hasText(searchRequest.getAgentId(), "AgentId cannot be empty");
		Assert.hasText(searchRequest.getDocVectorType(), "DocVectorType cannot be empty");

		Filter.Expression filter = dynamicFilterService.buildDynamicFilter(searchRequest.getAgentId(),
				searchRequest.getDocVectorType());
		if (filter == null) {
			log.warn(
					"Dynamic filter returned null (no valid ids), returning empty result directly.AgentId: {}, VectorType: {}",
					searchRequest.getAgentId(), searchRequest.getDocVectorType());
			return Collections.emptyList();
		}

		HybridSearchRequest hybridRequest = HybridSearchRequest.builder()
			.query(searchRequest.getQuery())
			.topK(searchRequest.getTopK())
			.similarityThreshold(searchRequest.getSimilarityThreshold())
			.filterExpression(filter)
			.build();

		if (dataAgentProperties.getVectorStore().isEnableHybridSearch() && hybridRetrievalStrategy.isPresent()) {
			return hybridRetrievalStrategy.get().retrieve(hybridRequest);
		}
		log.debug("Hybrid search is not enabled. use vector-search only");
		List<Document> results = vectorStore.similaritySearch(hybridRequest.toVectorSearchRequest());
		log.debug("Search completed with vectorType: {}, found {} documents for SearchRequest: {}",
				searchRequest.getDocVectorType(), results.size(), searchRequest);
		return results;
	}

	/**
 * `deleteDocumentsByVectorType`：删除对象、解绑关系，或清理不再需要的数据。
 *
 * 它处在服务层，常见上游是 Controller、Workflow 节点或事件监听器，下游则可能是 Mapper、模型服务或外部组件。
 */
	@Override
	public Boolean deleteDocumentsByVectorType(String agentId, String vectorType) throws Exception {
		Assert.notNull(agentId, "AgentId cannot be null.");
		Assert.notNull(vectorType, "VectorType cannot be null.");

		Map<String, Object> metadata = new HashMap<>(Map.ofEntries(Map.entry(Constant.AGENT_ID, agentId),
				Map.entry(DocumentMetadataConstant.VECTOR_TYPE, vectorType)));

		return this.deleteDocumentsByMetedata(agentId, metadata);
	}

	/**
	 * 批量写入文档到向量库。
	 *
	 * 这里会先按文档类型校验 metadata 是否齐全，
	 * 因为向量库删除、过滤、召回都强依赖这些元数据。
	 */
	@Override
	public void addDocuments(String agentId, List<Document> documents) {
		Assert.notNull(agentId, "AgentId cannot be null.");
		Assert.notEmpty(documents, "Documents cannot be empty.");

		for (Document document : documents) {
			Assert.notNull(document.getMetadata(), "Document metadata cannot be null.");

			String vectorType = (String) document.getMetadata().get(DocumentMetadataConstant.VECTOR_TYPE);

			if (DocumentMetadataConstant.TABLE.equals(vectorType)
					|| DocumentMetadataConstant.COLUMN.equals(vectorType)) {
				Assert.isTrue(document.getMetadata().containsKey(Constant.DATASOURCE_ID),
						"Document metadata must contain datasourceId for TABLE/COLUMN type.");
			}
			else {
				Assert.isTrue(document.getMetadata().containsKey(Constant.AGENT_ID),
						"Document metadata must contain agentId.");
				Assert.isTrue(document.getMetadata().get(Constant.AGENT_ID).equals(agentId),
						"Document metadata agentId does not match.");
			}
		}
		vectorStore.add(documents);
	}

	/**
 * `deleteDocumentsByMetadata`：删除对象、解绑关系，或清理不再需要的数据。
 *
 * 它处在服务层，常见上游是 Controller、Workflow 节点或事件监听器，下游则可能是 Mapper、模型服务或外部组件。
 */
	@Override
	public Boolean deleteDocumentsByMetadata(Map<String, Object> metadata) {
		Assert.notNull(metadata, "Metadata cannot be null.");
		String filterExpression = buildFilterExpressionString(metadata);

		if (vectorStore instanceof SimpleVectorStore) {
			batchDelDocumentsWithFilter(filterExpression);
		}
		else {
			vectorStore.delete(filterExpression);
		}

		return true;
	}

	/**
	 * 按 agentId + metadata 删除文档。
	 *
	 * 这里会强制补上 agentId 过滤条件，避免调用方漏传导致误删其他 Agent 的文档。
	 */
	@Override
	public Boolean deleteDocumentsByMetedata(String agentId, Map<String, Object> metadata) {
		Assert.hasText(agentId, "AgentId cannot be empty.");
		Assert.notNull(metadata, "Metadata cannot be null.");
		metadata.put(Constant.AGENT_ID, agentId);
		String filterExpression = buildFilterExpressionString(metadata);

		if (vectorStore instanceof SimpleVectorStore) {
			// SimpleVectorStore 目前不支持直接按 metadata 删除，所以退化成“查出 id 再批量删”。
			batchDelDocumentsWithFilter(filterExpression);
		}
		else {
			vectorStore.delete(filterExpression);
		}

		return true;
	}

	/**
	 * 对不支持 metadata 删除的向量库做“查出 ID 再删除”的批处理降级。
	 *
	 * 这里要分批查，是因为部分向量数据库对 topK 有上限。
	 */
	private void batchDelDocumentsWithFilter(String filterExpression) {
		Set<String> seenDocumentIds = new HashSet<>();
		List<Document> batch;
		int newDocumentsCount;
		int totalDeleted = 0;

		do {
			batch = vectorStore.similaritySearch(SearchRequest.builder()
				.query(DEFAULT)
				.filterExpression(filterExpression)
				.similarityThreshold(0.0)
				.topK(dataAgentProperties.getVectorStore().getBatchDelTopkLimit())
				.build());

			List<String> idsToDelete = new ArrayList<>();
			newDocumentsCount = 0;

			for (Document doc : batch) {
				if (seenDocumentIds.add(doc.getId())) {
					idsToDelete.add(doc.getId());
					newDocumentsCount++;
				}
			}

			if (!idsToDelete.isEmpty()) {
				vectorStore.delete(idsToDelete);
				totalDeleted += idsToDelete.size();
			}

		}
		while (newDocumentsCount > 0);

		log.info("Deleted {} documents with filter expression: {}", totalDeleted, filterExpression);
	}

	/**
 * `getDocumentsForAgent`：读取当前场景所需的数据或状态。
 *
 * 它处在服务层，常见上游是 Controller、Workflow 节点或事件监听器，下游则可能是 Mapper、模型服务或外部组件。
 */
	@Override
	public List<Document> getDocumentsForAgent(String agentId, String query, String vectorType) {
		int defaultTopK = dataAgentProperties.getVectorStore().getDefaultTopkLimit();
		double defaultThreshold = dataAgentProperties.getVectorStore().getDefaultSimilarityThreshold();

		return getDocumentsForAgent(agentId, query, vectorType, defaultTopK, defaultThreshold);
	}

	/**
 * `getDocumentsForAgent`：读取当前场景所需的数据或状态。
 *
 * 它处在服务层，常见上游是 Controller、Workflow 节点或事件监听器，下游则可能是 Mapper、模型服务或外部组件。
 */
	@Override
	public List<Document> getDocumentsForAgent(String agentId, String query, String vectorType, int topK,
			double threshold) {
		AgentSearchRequest searchRequest = AgentSearchRequest.builder()
			.agentId(agentId)
			.docVectorType(vectorType)
			.query(query)
			.topK(topK)
			.similarityThreshold(threshold)
			.build();
		return search(searchRequest);
	}

	/**
 * `getDocumentsOnlyByFilter`：按指定条件查询对象或结果列表。
 *
 * 它处在服务层，常见上游是 Controller、Workflow 节点或事件监听器，下游则可能是 Mapper、模型服务或外部组件。
 */
	@Override
	public List<Document> getDocumentsOnlyByFilter(Filter.Expression filterExpression, Integer topK) {
		Assert.notNull(filterExpression, "filterExpression cannot be null.");
		if (topK == null) {
			topK = dataAgentProperties.getVectorStore().getDefaultTopkLimit();
		}
		SearchRequest searchRequest = SearchRequest.builder()
			.query(DEFAULT)
			.topK(topK)
			.filterExpression(filterExpression)
			.similarityThreshold(0.0)
			.build();
		return vectorStore.similaritySearch(searchRequest);
	}

	/**
	 * 判断某个 Agent 是否已经写入过任何向量文档。
	 *
	 * 这里只查 1 条，目的不是拿结果，而是快速做存在性判断。
	 */
	@Override
	public boolean hasDocuments(String agentId) {
		List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
			.query(DEFAULT)
			.filterExpression(buildFilterExpressionString(Map.of(Constant.AGENT_ID, agentId)))
			.topK(1)
			.similarityThreshold(0.0)
			.build());
		return !docs.isEmpty();
	}

}
