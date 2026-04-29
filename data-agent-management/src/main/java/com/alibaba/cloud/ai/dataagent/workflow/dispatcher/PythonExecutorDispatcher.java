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

import com.alibaba.cloud.ai.dataagent.properties.CodeExecutorProperties;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import lombok.extern.slf4j.Slf4j;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.PYTHON_ANALYZE_NODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PYTHON_EXECUTE_NODE_OUTPUT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PYTHON_FALLBACK_MODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PYTHON_GENERATE_NODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PYTHON_IS_SUCCESS;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PYTHON_TRIES_COUNT;
import static com.alibaba.cloud.ai.graph.StateGraph.END;

/**
 * Python 执行分发器。
 *
 * 这个 Dispatcher 专门处理 Python 执行后的三种分支：
 * - 成功：进入结果分析节点。
 * - 失败但仍可重试：回到 Python 生成节点重新产码。
 * - 失败且进入降级：直接进入分析节点，由分析节点输出兜底说明。
 */
@Slf4j
public class PythonExecutorDispatcher implements EdgeAction {

	private final CodeExecutorProperties codeExecutorProperties;

	public PythonExecutorDispatcher(CodeExecutorProperties codeExecutorProperties) {
		this.codeExecutorProperties = codeExecutorProperties;
	}

	/**
 * `apply`：执行当前类对外暴露的一步核心操作。
 *
 * 阅读这个方法时，建议同时关注它依赖了什么输入，以及结果最后会被哪一层继续消费。
 */
	@Override
	public String apply(OverAllState state) throws Exception {
		boolean isFallbackMode = StateUtil.getObjectValue(state, PYTHON_FALLBACK_MODE, Boolean.class, false);
		if (isFallbackMode) {
			log.warn("Python 执行进入降级模式，跳过重试，直接进入分析节点。");
			return PYTHON_ANALYZE_NODE;
		}

		boolean isSuccess = StateUtil.getObjectValue(state, PYTHON_IS_SUCCESS, Boolean.class, false);
		if (!isSuccess) {
			String message = StateUtil.getStringValue(state, PYTHON_EXECUTE_NODE_OUTPUT);
			log.error("Python Executor Node Error: {}", message);
			int tries = StateUtil.getObjectValue(state, PYTHON_TRIES_COUNT, Integer.class, 0);
			if (tries >= codeExecutorProperties.getPythonMaxTriesCount()) {
				log.error("Python 执行失败且已超过最大重试次数（已尝试次数：{}），流程终止。", tries);
				return END;
			}
			else {
				return PYTHON_GENERATE_NODE;
			}
		}

		return PYTHON_ANALYZE_NODE;
	}

}
