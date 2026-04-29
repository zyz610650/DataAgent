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
package com.alibaba.cloud.ai.dataagent.connector.ddl;

import com.alibaba.cloud.ai.dataagent.enums.BizDataSourceTypeEnum;

/**
 * Ddl：底层连接或方言适配组件。
 *
 * 它负责把统一的DDL调用抽象转换成具体数据库、连接池、DDL 或执行动作。
 * 阅读时重点看统一接口如何落到不同数据库实现上。
 */
public interface Ddl {

	BizDataSourceTypeEnum getDataSourceType();

	default boolean supportedDataSourceType(String type) {
		return getDataSourceType().getTypeName().equals(type);
	}

	default boolean supportedDataSourceType(BizDataSourceTypeEnum type) {
		return getDataSourceType().equals(type);
	}

	default String getDdlType() {
		return getDataSourceType().getProtocol() + "@" + getDataSourceType().getDialect();
	}

}
