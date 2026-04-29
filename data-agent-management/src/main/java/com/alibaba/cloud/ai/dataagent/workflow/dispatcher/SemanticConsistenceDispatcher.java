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
package com.alibaba.cloud.ai.dataagent.workflow.dispatcher;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import lombok.extern.slf4j.Slf4j;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.SEMANTIC_CONSISTENCY_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_EXECUTE_NODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.SQL_GENERATE_NODE;

/**
 * 语义一致性分发器。
 *
 * 当前阶段的职责很明确：
 * - 语义校验通过，SQL 可以进入执行阶段。
 * - 语义校验不通过，回到 SQL 生成阶段重新修正。
 *
 * 这类 Dispatcher 是 Graph 工作流中最常见的模式之一：
 * 只读状态，不做副作用，只负责把布尔结果翻译成下一跳节点名。
 */
@Slf4j
public class SemanticConsistenceDispatcher implements EdgeAction {

	/**
 * `apply`：执行当前类对外暴露的一步核心操作。
 *
 * 阅读这个方法时，建议同时关注它依赖了什么输入，以及结果最后会被哪一层继续消费。
 */
	@Override
	public String apply(OverAllState state) {
		Boolean validate = (Boolean) state.value(SEMANTIC_CONSISTENCY_NODE_OUTPUT).orElse(false);
		log.info("语义一致性校验结果: {}，开始进行路由判断。", validate);
		if (validate) {
			log.info("语义一致性校验通过，跳转到 SQL 执行节点。");
			return SQL_EXECUTE_NODE;
		}
		else {
			log.info("语义一致性校验未通过，跳转回 SQL 生成节点。");
			return SQL_GENERATE_NODE;
		}
	}

}
