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
package com.alibaba.cloud.ai.dataagent.dto.search;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.vectorstore.filter.Filter;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
/**
 * HybridSearchRequest：请求参数或中间结果传输对象。
 *
 * 它主要负责承载混合检索请求相关字段，本身不放复杂业务逻辑。
 * 阅读时重点看字段语义、默认值，以及这些字段最终会在哪一层被消费。
 */
public class HybridSearchRequest implements Serializable {

	// === 基础参数 ===
	private String query;

	private Integer topK;

	private double similarityThreshold = 0.0;

	private Filter.Expression filterExpression;

	// 向量检索权重
	@Builder.Default
	private Double vectorWeight = 0.5;

	// 关键词检索权重
	@Builder.Default
	private Double keywordWeight = 0.5;

	// 是否开启重排序模型
	@Builder.Default
	private boolean useRerank = false;

	// 扩展参数包将来某种数据库的特有参数
	@Builder.Default
	private Map<String, Object> extraParams = new HashMap<>();

	public org.springframework.ai.vectorstore.SearchRequest toVectorSearchRequest() {
		return org.springframework.ai.vectorstore.SearchRequest.builder()
			.query(this.query)
			.topK(this.topK)
			.similarityThreshold(this.similarityThreshold)
			.filterExpression(this.filterExpression)
			.build();
	}

}
