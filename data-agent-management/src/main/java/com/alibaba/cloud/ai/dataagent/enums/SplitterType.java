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
package com.alibaba.cloud.ai.dataagent.enums;

import lombok.Getter;

@Getter
/**
 * SplitterType：枚举定义。
 *
 * 它把切分器Type相关的固定取值集中管理，避免状态值散落在各个业务类中硬编码。
 * 重点关注每个枚举值在参数校验、分支判断和外部协议中的含义。
 */
public enum SplitterType {

	TOKEN("token"), RECURSIVE("recursive"), SENTENCE("sentence"), PARAGRAPH("paragraph"), SEMANTIC("semantic");

	private final String value;

	SplitterType(String value) {
		this.value = value;
	}

}
