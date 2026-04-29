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

import com.alibaba.cloud.ai.dataagent.bo.schema.ColumnInfoBO;
import com.alibaba.cloud.ai.dataagent.bo.schema.DatabaseInfoBO;
import com.alibaba.cloud.ai.dataagent.bo.schema.ForeignKeyInfoBO;
import com.alibaba.cloud.ai.dataagent.bo.schema.ResultSetBO;
import com.alibaba.cloud.ai.dataagent.bo.schema.SchemaInfoBO;
import com.alibaba.cloud.ai.dataagent.bo.schema.TableInfoBO;
import com.alibaba.cloud.ai.dataagent.util.SqlUtil;

import java.sql.Connection;
import java.util.List;

public abstract class AbstractJdbcDdl implements Ddl {

	@Deprecated
	/**
 * `showDatabases`：执行当前类对外暴露的一步核心操作。
 *
 * 它位于底层适配层，目标是把统一抽象翻译成具体数据库或执行环境可以理解的动作。
 */
	public abstract List<DatabaseInfoBO> showDatabases(Connection connection);

	/**
 * `showSchemas`：执行当前类对外暴露的一步核心操作。
 *
 * 它位于底层适配层，目标是把统一抽象翻译成具体数据库或执行环境可以理解的动作。
 */
	public abstract List<SchemaInfoBO> showSchemas(Connection connection);

	/**
 * `showTables`：执行当前类对外暴露的一步核心操作。
 *
 * 它位于底层适配层，目标是把统一抽象翻译成具体数据库或执行环境可以理解的动作。
 */
	public abstract List<TableInfoBO> showTables(Connection connection, String schema, String tablePattern);

	/**
 * `fetchTables`：执行当前类对外暴露的一步核心操作。
 *
 * 它位于底层适配层，目标是把统一抽象翻译成具体数据库或执行环境可以理解的动作。
 */
	public abstract List<TableInfoBO> fetchTables(Connection connection, String schema, List<String> tables);

	/**
 * `showColumns`：执行当前类对外暴露的一步核心操作。
 *
 * 它位于底层适配层，目标是把统一抽象翻译成具体数据库或执行环境可以理解的动作。
 */
	public abstract List<ColumnInfoBO> showColumns(Connection connection, String schema, String table);

	/**
 * `showForeignKeys`：执行当前类对外暴露的一步核心操作。
 *
 * 它位于底层适配层，目标是把统一抽象翻译成具体数据库或执行环境可以理解的动作。
 */
	public abstract List<ForeignKeyInfoBO> showForeignKeys(Connection connection, String schema, List<String> tables);

	/**
 * `sampleColumn`：执行当前类对外暴露的一步核心操作。
 *
 * 它位于底层适配层，目标是把统一抽象翻译成具体数据库或执行环境可以理解的动作。
 */
	public abstract List<String> sampleColumn(Connection connection, String schema, String table, String column);

	/**
 * `scanTable`：执行当前类对外暴露的一步核心操作。
 *
 * 它位于底层适配层，目标是把统一抽象翻译成具体数据库或执行环境可以理解的动作。
 */
	public abstract ResultSetBO scanTable(Connection connection, String schema, String table);

	/**
 * `getSelectSql`：读取当前场景所需的数据或状态。
 *
 * 它位于底层适配层，目标是把统一抽象翻译成具体数据库或执行环境可以理解的动作。
 */
	public String getSelectSql(String typeName, String tableName, String columnNames, int limit) {
		return SqlUtil.buildSelectSql(typeName, tableName, columnNames, limit);
	}

}
