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
package com.alibaba.cloud.ai.dataagent.service.nl2sql;

import com.alibaba.cloud.ai.dataagent.bo.DbConfigBO;
import com.alibaba.cloud.ai.dataagent.dto.prompt.SemanticConsistencyDTO;
import com.alibaba.cloud.ai.dataagent.dto.prompt.SqlGenerationDTO;
import com.alibaba.cloud.ai.dataagent.dto.schema.SchemaDTO;
import com.alibaba.cloud.ai.dataagent.prompt.PromptHelper;
import com.alibaba.cloud.ai.dataagent.service.llm.LlmService;
import com.alibaba.cloud.ai.dataagent.util.ChatResponseUtil;
import com.alibaba.cloud.ai.dataagent.util.FluxUtil;
import com.alibaba.cloud.ai.dataagent.util.JsonParseUtil;
import com.alibaba.cloud.ai.dataagent.util.JsonUtil;
import com.alibaba.cloud.ai.dataagent.util.MarkdownParserUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import static com.alibaba.cloud.ai.dataagent.prompt.PromptHelper.buildMixMacSqlDbPrompt;
import static com.alibaba.cloud.ai.dataagent.prompt.PromptHelper.buildMixSelectorPrompt;

/**
 * NL2SQL 领域服务实现。
 *
 * 这个类专注于“组织 Prompt 并调用 LLM”，主要承担三类任务：
 * 1. 生成 SQL。
 * 2. 修复已有 SQL。
 * 3. 利用 LLM 做 schema 精筛和语义一致性校验。
 *
 * 它不负责 HTTP 协议，也不负责工作流路由；
 * 在系统分层里，它属于纯业务能力层，被 Graph 节点按需调用。
 *
 * 关键框架 API：
 * - {@link Flux}：保留模型流式输出，便于上游继续做流式透传。
 * - {@link ChatResponse}：Spring AI 对一次模型输出片段的统一抽象。
 * - {@link TypeReference}：反序列化泛型 JSON 时的标准写法。
 */
@Slf4j
@Service
@AllArgsConstructor
public class Nl2SqlServiceImpl implements Nl2SqlService {

	public final LlmService llmService;

	private final JsonParseUtil jsonParseUtil;

	/**
	 * 执行语义一致性检查。
	 *
	 * 这一步通常发生在 SQL 生成之后、执行之前，用于判断：
	 * “当前 SQL 虽然语法可能合法，但是否真的符合用户问题和语义模型约束”。
	 */
	@Override
	public Flux<ChatResponse> performSemanticConsistency(SemanticConsistencyDTO semanticConsistencyDTO) {
		String semanticConsistencyPrompt = PromptHelper.buildSemanticConsistenPrompt(semanticConsistencyDTO);
		log.debug("semanticConsistencyPrompt as follows \n {} \n", semanticConsistencyPrompt);
		return llmService.callUser(semanticConsistencyPrompt);
	}

	/**
	 * 生成新 SQL，或修复已有 SQL。
	 *
	 * 设计意图：
	 * - 如果 `sqlGenerationDTO` 里已经带 SQL，说明当前更像是重试/修复场景。
	 * - 如果没有 SQL，说明是首次生成，走标准 SQL 生成提示词。
	 *
	 * 这样拆分能降低模型误判任务目标的概率。
	 */
	@Override
	public Flux<String> generateSql(SqlGenerationDTO sqlGenerationDTO) {
		String sql = sqlGenerationDTO.getSql();
		log.info("Generating SQL for query: {}, hasExistingSql: {}, dialect: {}",
				sqlGenerationDTO.getExecutionDescription(), StringUtils.hasText(sql), sqlGenerationDTO.getDialect());

		Flux<String> newSqlFlux;
		if (sql != null && !sql.isEmpty()) {
			log.debug("Using SQL error fixer for existing SQL: {}", sql);
			String errorFixerPrompt = PromptHelper.buildSqlErrorFixerPrompt(sqlGenerationDTO);
			log.debug("SQL error fixer prompt as follows \n {} \n", errorFixerPrompt);
			newSqlFlux = llmService.toStringFlux(llmService.callUser(errorFixerPrompt));
			log.info("SQL error fixing completed");
		}
		else {
			log.debug("Generating new SQL from scratch");
			String prompt = PromptHelper.buildNewSqlGeneratorPrompt(sqlGenerationDTO);
			log.debug("New SQL generator prompt as follows \n {} \n", prompt);
			newSqlFlux = llmService.toStringFlux(llmService.callSystem(prompt));
			log.info("New SQL generation completed");
		}

		return newSqlFlux;
	}

	/**
	 * 根据“schema 缺失建议”再做一次表级精筛。
	 *
	 * 这是一条补偿链路：
	 * 当 SQL 生成结果提示“当前 schema 还缺某些表信息”时，
	 * 系统会让模型再从 schema 中补挑一次相关表，尽量减少下次生成时的无关上下文。
	 */
	private Flux<ChatResponse> fineSelect(SchemaDTO schemaDTO, String sqlGenerateSchemaMissingAdvice,
			Consumer<Set<String>> resultConsumer) {
		log.debug("Fine selecting tables based on advice: {}", sqlGenerateSchemaMissingAdvice);
		String schemaInfo = buildMixMacSqlDbPrompt(schemaDTO, true);
		String prompt = " 建议：" + sqlGenerateSchemaMissingAdvice
				+ " \n 请按照建议进行返回相关表的名称，只返回建议中提到的表名，返回格式为：[\"a\",\"b\",\"c\"] \n " + schemaInfo;
		log.debug("Built table selection with advice prompt as follows \n {} \n", prompt);
		StringBuilder sb = new StringBuilder();
		return llmService.callUser(prompt).doOnNext(r -> {
			String text = r.getResult().getOutput().getText();
			sb.append(text);
		}).doOnComplete(() -> {
			String content = sb.toString();
			if (!content.trim().isEmpty()) {
				String jsonContent = MarkdownParserUtil.extractText(content);
				List<String> tableList;
				try {
					// 模型经常把 JSON 放在 markdown code block 中，先抽文本再做反序列化更稳妥。
					tableList = JsonUtil.getObjectMapper().readValue(jsonContent, new TypeReference<List<String>>() {
					});
				}
				catch (Exception e) {
					log.error("Failed to parse table selection response: {}", jsonContent, e);
					throw new IllegalStateException(jsonContent);
				}
				if (tableList != null && !tableList.isEmpty()) {
					Set<String> selectedTables = tableList.stream()
						.map(String::toLowerCase)
						.collect(Collectors.toSet());
					log.debug("Selected {} tables based on advice: {}", selectedTables.size(), selectedTables);
					resultConsumer.accept(selectedTables);
					return;
				}
			}
			log.debug("No tables selected based on advice");
			resultConsumer.accept(new HashSet<>());
		});
	}

	/**
	 * 结合用户问题、证据和可选补充建议，对 schema 做细筛。
	 *
	 * 输出方式有两层：
	 * 1. 返回 `Flux<ChatResponse>`，让上游仍然能看到模型流式输出。
	 * 2. 通过 `dtoConsumer` 回传筛选后的 `SchemaDTO`，供后续 SQL 生成节点直接使用。
	 *
	 * `FluxUtil.cascadeFlux(...)` 可以理解为“第一段流结束后，根据聚合结果再串联第二段流”。
	 * 这种写法比大量嵌套回调更适合维护复杂的流式编排逻辑。
	 */
	@Override
	public Flux<ChatResponse> fineSelect(SchemaDTO schemaDTO, String query, String evidence,
			String sqlGenerateSchemaMissingAdvice, DbConfigBO specificDbConfig, Consumer<SchemaDTO> dtoConsumer) {
		log.debug("Fine selecting schema for query: {} with evidences and specificDbConfig: {}", query,
				specificDbConfig != null ? specificDbConfig.getUrl() : "default");

		String prompt = buildMixSelectorPrompt(evidence, query, schemaDTO);
		log.debug("Built schema fine selection prompt as follows \n {} \n", prompt);

		Set<String> selectedTables = new HashSet<>();

		return FluxUtil.<ChatResponse, String>cascadeFlux(llmService.callUser(prompt), content -> {
			Flux<ChatResponse> nextFlux;
			if (sqlGenerateSchemaMissingAdvice != null) {
				log.debug("Adding tables from schema missing advice");
				nextFlux = this.fineSelect(schemaDTO, sqlGenerateSchemaMissingAdvice, selectedTables::addAll);
			}
			else {
				nextFlux = Flux.empty();
			}
			return nextFlux.doOnComplete(() -> {
				if (!content.trim().isEmpty()) {
					String jsonContent = MarkdownParserUtil.extractText(content);
					List<String> tableList;
					try {
						tableList = jsonParseUtil.tryConvertToObject(jsonContent, new TypeReference<List<String>>() {
						});
					}
					catch (Exception e) {
						// 这里常见的失败不是 JSON 库问题，而是模型没有按约定输出数组结构。
						log.error("Failed to parse fine selection response: {}", jsonContent, e);
						throw new IllegalStateException(jsonContent);
					}
					if (tableList != null && !tableList.isEmpty()) {
						selectedTables.addAll(tableList.stream().map(String::toLowerCase).collect(Collectors.toSet()));
						if (schemaDTO.getTable() != null) {
							int originalTableCount = schemaDTO.getTable().size();
							schemaDTO.getTable()
								.removeIf(table -> !selectedTables.contains(table.getName().toLowerCase()));
							int finalTableCount = schemaDTO.getTable().size();
							log.debug("Fine selection completed: {} -> {} tables, selected tables: {}",
									originalTableCount, finalTableCount, selectedTables);
						}
					}
				}
				dtoConsumer.accept(schemaDTO);
			});
		}, flux -> flux.map(ChatResponseUtil::getText)
			.collect(StringBuilder::new, StringBuilder::append)
			.map(StringBuilder::toString));
	}

}
