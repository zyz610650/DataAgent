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
package com.alibaba.cloud.ai.dataagent.service.aimodelconfig;

import com.alibaba.cloud.ai.dataagent.dto.ModelConfigDTO;
import com.alibaba.cloud.ai.dataagent.enums.ModelType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Component;

/**
 * 当前激活 AI 模型的注册中心。
 *
 * 它的职责不是持久化模型配置，而是做“运行时读缓存 + 懒加载实例化”：
 * - Chat 链路从这里拿 `ChatClient`
 * - Embedding 链路从这里拿 `EmbeddingModel`
 *
 * 为什么需要这个层：
 * 1. 数据库存的是配置，不是可直接调用的模型实例。
 * 2. 模型实例化通常比较重，不适合每次请求都重新创建。
 * 3. 项目支持热切换模型，所以需要一个可刷新缓存的统一入口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiModelRegistry {

	private final DynamicModelFactory modelFactory;

	private final ModelConfigDataService modelConfigDataService;

	/**
	 * volatile 保证不同线程都能看到最新缓存引用。
	 * 配合双重检查锁，既降低并发开销，也避免重复初始化。
	 */
	private volatile ChatClient currentChatClient;

	private volatile EmbeddingModel currentEmbeddingModel;

	/**
	 * 获取当前激活的 ChatClient。
	 *
	 * 核心策略：
	 * - 懒加载：第一次真正用到时才初始化
	 * - 缓存：后续请求直接复用
	 * - 热刷新：调用 `refreshChat()` 后，下次访问会按最新配置重新初始化
	 *
	 * 这里返回的是 `ChatClient` 而不是 `ChatModel`，是因为业务层通常更关心“怎么对话调用”，
	 * 而不是底层模型细节。
	 */
	public ChatClient getChatClient() {
		if (currentChatClient == null) {
			synchronized (this) {
				if (currentChatClient == null) {
					log.info("Initializing global ChatClient...");
					try {
						ModelConfigDTO config = modelConfigDataService.getActiveConfigByType(ModelType.CHAT);
						if (config != null) {
							ChatModel chatModel = modelFactory.createChatModel(config);
							currentChatClient = ChatClient.builder(chatModel).build();
						}
					}
					catch (Exception e) {
						log.error("Failed to initialize ChatClient: {}", e.getMessage(), e);
					}

					if (currentChatClient == null) {
						throw new RuntimeException(
								"No active CHAT model configured. Please configure it in the dashboard.");
					}
				}
			}
		}
		return currentChatClient;
	}

	/**
	 * 获取当前激活的 EmbeddingModel。
	 *
	 * 和 ChatClient 类似，这里同样是懒加载 + 缓存。
	 * 但 Embedding 链路多了一层特别处理：当真的没有可用配置时，返回 Dummy 模型而不是直接 null。
	 *
	 * 原因：
	 * - 一些 VectorStore Starter 会在启动期直接调用 `dimensions()`
	 * - 如果这里返回 null，系统会在 Bean 装配阶段直接失败
	 * - Dummy 模型的目标不是提供真实能力，而是让系统先启动，再提示用户补配置
	 */
	public EmbeddingModel getEmbeddingModel() {
		if (currentEmbeddingModel == null) {
			synchronized (this) {
				if (currentEmbeddingModel == null) {
					log.info("Initializing global EmbeddingModel...");
					try {
						ModelConfigDTO config = modelConfigDataService.getActiveConfigByType(ModelType.EMBEDDING);
						if (config != null) {
							currentEmbeddingModel = modelFactory.createEmbeddingModel(config);
						}
					}
					catch (Exception e) {
						log.error("Failed to initialize EmbeddingModel: {}", e.getMessage());
					}

					if (currentEmbeddingModel == null) {
						log.warn("Using DummyEmbeddingModel for fallback.");
						currentEmbeddingModel = new DummyEmbeddingModel();
					}
				}
			}
		}
		return currentEmbeddingModel;
	}

	/**
	 * 清空 Chat 缓存。
	 *
	 * 这不会立即创建新模型，只是让下一次访问时按最新配置重新初始化。
	 */
	public void refreshChat() {
		this.currentChatClient = null;
		log.info("Chat cache cleared.");
	}

	/**
 * `refreshEmbedding`：初始化、装配或刷新当前能力所需的运行时状态。
 *
 * 它定义的是服务契约，真正的落地逻辑通常在对应的实现类中完成。
 */
	public void refreshEmbedding() {
		this.currentEmbeddingModel = null;
		log.info("Embedding cache cleared.");
	}

	/**
	 * 启动兜底用的 Dummy Embedding 模型。
	 *
	 * 这个内部类的目标不是提供真实 embedding 能力，而是：
	 * 1. 让依赖 `EmbeddingModel` 的 Starter 能先完成启动
	 * 2. 在真正发生 embedding 调用时，再以明确错误暴露“尚未配置有效模型”
	 */
	private static class DummyEmbeddingModel implements EmbeddingModel {

		/**
 * `call`：触发一次真正的执行动作，并把结果返回给上游。
 *
 * 它定义的是服务契约，真正的落地逻辑通常在对应的实现类中完成。
 */
		@Override
		public EmbeddingResponse call(EmbeddingRequest request) {
			throw new RuntimeException("No active EMBEDDING model. Please configure it first!");
		}

		/**
		 * 对单个 Document 的占位实现。
		 *
		 * 返回空向量只是为了满足接口契约，不能用于真实语义检索。
		 */
		@Override
		public float[] embed(Document document) {
			return new float[0];
		}

		/**
 * `embed`：执行当前类对外暴露的一步核心操作。
 *
 * 它定义的是服务契约，真正的落地逻辑通常在对应的实现类中完成。
 */
		@Override
		public float[] embed(String text) {
			return new float[0];
		}

		/**
 * `embed`：执行当前类对外暴露的一步核心操作。
 *
 * 它定义的是服务契约，真正的落地逻辑通常在对应的实现类中完成。
 */
		@Override
		public List<float[]> embed(List<String> texts) {
			return List.of();
		}

		/**
 * `embedForResponse`：执行当前类对外暴露的一步核心操作。
 *
 * 它定义的是服务契约，真正的落地逻辑通常在对应的实现类中完成。
 */
		@Override
		public EmbeddingResponse embedForResponse(List<String> texts) {
			return null;
		}

		/**
		 * 返回一个兼容启动期校验的默认向量维度。
		 *
		 * 这里选择 1536 是因为它是常见 embedding 模型的维度之一，
		 * 主要目的是尽量降低 Starter 初始化阶段的兼容性问题。
		 */
		@Override
		public int dimensions() {
			return 1536;
		}

	}

}
