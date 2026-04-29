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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpHost;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

/**
 * 动态模型工厂。
 *
 * 这个类负责把数据库中的“模型配置数据”转成真正可调用的 Spring AI 模型实例。
 * 在当前项目里，它刻意统一使用 OpenAI 协议适配层：
 * - 不同厂商通过 `baseUrl` 和路径差异适配
 * - 上层业务仍然只看到统一的 ChatModel / EmbeddingModel 抽象
 *
 * 这样做的好处：
 * 1. 减少多厂商 SDK 分支代码。
 * 2. 让自定义兼容 OpenAI 协议的模型服务也能被接入。
 * 3. 把代理、鉴权、路径定制都集中收敛在一个工厂里。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicModelFactory {

	/**
	 * 创建聊天模型。
	 *
	 * 实现步骤：
	 * 1. 校验基础配置完整性。
	 * 2. 构造 `OpenAiApi`，它是底层 HTTP 通信入口。
	 * 3. 构造 `OpenAiChatOptions`，写入默认模型名、温度、最大 token 等运行选项。
	 * 4. 返回 `OpenAiChatModel`。
	 *
	 * 关键框架 API：
	 * - `OpenAiApi`：Spring AI 中基于 OpenAI 协议的底层客户端。
	 * - `OpenAiChatModel`：真正被业务调用的聊天模型实现。
	 */
	public ChatModel createChatModel(ModelConfigDTO config) {

		log.info("Creating NEW ChatModel instance. Provider: {}, Model: {}, BaseUrl: {}", config.getProvider(),
				config.getModelName(), config.getBaseUrl());
		checkBasic(config);

		String apiKey = StringUtils.hasText(config.getApiKey()) ? config.getApiKey() : "";
		OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
			.apiKey(apiKey)
			.baseUrl(config.getBaseUrl())
			.restClientBuilder(getProxiedRestClientBuilder(config))
			.webClientBuilder(getProxiedWebClientBuilder(config));

		if (StringUtils.hasText(config.getCompletionsPath())) {
			apiBuilder.completionsPath(config.getCompletionsPath());
		}
		OpenAiApi openAiApi = apiBuilder.build();

		OpenAiChatOptions openAiChatOptions = OpenAiChatOptions.builder()
			.model(config.getModelName())
			.temperature(config.getTemperature())
			.maxTokens(config.getMaxTokens())
			.streamUsage(true)
			.build();

		return OpenAiChatModel.builder().openAiApi(openAiApi).defaultOptions(openAiChatOptions).build();
	}

	/**
	 * 创建 Embedding 模型。
	 *
	 * 逻辑和 ChatModel 类似，只是目标实现换成了 `OpenAiEmbeddingModel`。
	 */
	public EmbeddingModel createEmbeddingModel(ModelConfigDTO config) {
		log.info("Creating NEW EmbeddingModel instance. Provider: {}, Model: {}, BaseUrl: {}", config.getProvider(),
				config.getModelName(), config.getBaseUrl());
		checkBasic(config);

		String apiKey = StringUtils.hasText(config.getApiKey()) ? config.getApiKey() : "";
		OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
			.apiKey(apiKey)
			.baseUrl(config.getBaseUrl())
			.restClientBuilder(getProxiedRestClientBuilder(config))
			.webClientBuilder(getProxiedWebClientBuilder(config));

		if (StringUtils.hasText(config.getEmbeddingsPath())) {
			apiBuilder.embeddingsPath(config.getEmbeddingsPath());
		}

		OpenAiApi openAiApi = apiBuilder.build();
		return new OpenAiEmbeddingModel(openAiApi, MetadataMode.EMBED,
				OpenAiEmbeddingOptions.builder().model(config.getModelName()).build(),
				RetryUtils.DEFAULT_RETRY_TEMPLATE);
	}

	/**
	 * 基础配置校验。
	 *
	 * 特殊规则：
	 * - provider 不是 `custom` 时，要求必须有 apiKey
	 * - `custom` 模式允许某些本地兼容服务没有真实 API Key
	 */
	private static void checkBasic(ModelConfigDTO config) {
		Assert.hasText(config.getBaseUrl(), "baseUrl must not be empty");
		if (!"custom".equalsIgnoreCase(config.getProvider())) {
			Assert.hasText(config.getApiKey(), "apiKey must not be empty");
		}
		Assert.hasText(config.getModelName(), "modelName must not be empty");
	}

	/**
	 * 构造同步 HTTP 调用使用的 RestClient.Builder，并在需要时接入代理。
	 *
	 * 为什么同步和异步要分开：
	 * - Spring AI 内部既可能走同步调用，也可能走 WebFlux 异步调用
	 * - 两套客户端使用的底层 HTTP 库不同，所以代理配置也要分别装配
	 */
	private RestClient.Builder getProxiedRestClientBuilder(ModelConfigDTO config) {
		if (config.getProxyEnabled() == null || !config.getProxyEnabled()) {
			return RestClient.builder();
		}

		log.info("[Proxy-Init] Model [{}] is using SYNC proxy -> {}:{}", config.getModelName(), config.getProxyHost(),
				config.getProxyPort());

		BasicCredentialsProvider credsProvider = new BasicCredentialsProvider();
		if (StringUtils.hasText(config.getProxyUsername())) {
			log.info("[Proxy-Auth] Enabling Basic Auth for SYNC proxy, user: {}", config.getProxyUsername());
			credsProvider.setCredentials(new AuthScope(config.getProxyHost(), config.getProxyPort()),
					new UsernamePasswordCredentials(config.getProxyUsername(),
							config.getProxyPassword().toCharArray()));
		}

		CloseableHttpClient httpClient = HttpClients.custom()
			.setProxy(new HttpHost(config.getProxyHost(), config.getProxyPort()))
			.setDefaultCredentialsProvider(credsProvider)
			.build();

		return RestClient.builder().requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient));
	}

	/**
 * `getProxiedWebClientBuilder`：读取当前场景所需的数据或状态。
 *
 * 它定义的是服务契约，真正的落地逻辑通常在对应的实现类中完成。
 */
	private WebClient.Builder getProxiedWebClientBuilder(ModelConfigDTO config) {
		if (config.getProxyEnabled() == null || !config.getProxyEnabled()) {
			return WebClient.builder();
		}

		log.info("[Proxy-Init] Model [{}] is using ASYNC (Netty) proxy -> {}:{}", config.getModelName(),
				config.getProxyHost(), config.getProxyPort());

		HttpClient nettyClient = HttpClient.create().responseTimeout(java.time.Duration.ofMinutes(3)).proxy(p -> {
			ProxyProvider.Builder proxyBuilder = p.type(ProxyProvider.Proxy.HTTP)
				.host(config.getProxyHost())
				.port(config.getProxyPort());

			if (StringUtils.hasText(config.getProxyUsername())) {
				log.info("[Proxy-Auth] Enabling Basic Auth for ASYNC proxy, user: {}", config.getProxyUsername());
				proxyBuilder.username(config.getProxyUsername()).password(s -> config.getProxyPassword());
			}
		});

		return WebClient.builder().clientConnector(new ReactorClientHttpConnector(nettyClient));
	}

}
