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
package com.alibaba.cloud.ai.dataagent.dto;

import com.alibaba.cloud.ai.dataagent.annotation.InEnum;
import com.alibaba.cloud.ai.dataagent.enums.ModelType;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
/**
 * ModelConfigDTO：请求参数或中间结果传输对象。
 *
 * 它主要负责承载模型配置相关字段，本身不放复杂业务逻辑。
 * 阅读时重点看字段语义、默认值，以及这些字段最终会在哪一层被消费。
 */
public class ModelConfigDTO {

	private Integer id;

	@NotBlank(message = "provider must not be empty")
	private String provider; // e.g. "openai", "deepseek"

	private String apiKey; // e.g. "https://api.openai.com"

	@NotBlank(message = "baseUrl must not be empty")
	private String baseUrl;

	@NotBlank(message = "modelName must not be empty")
	private String modelName;

	@NotBlank(message = "modelType must not be empty")
	@InEnum(value = ModelType.class, message = "CHAT/EMBEDDING 之一")
	private String modelType;

	// 仅当厂商路径非标准时填写，例如 "/custom/chat"
	private String completionsPath;

	// 仅当厂商路径非标准时填写
	private String embeddingsPath;

	private Double temperature = 0.0;

	private Integer maxTokens = 2000;

	private Boolean isActive = true;

	// 模型代理配置，默认关闭（使用直连）
	private Boolean proxyEnabled = false;

	private String proxyHost;

	private Integer proxyPort;

	private String proxyUsername;

	private String proxyPassword;

}
