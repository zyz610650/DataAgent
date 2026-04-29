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

import static com.alibaba.cloud.ai.graph.StateGraph.END;

/**
 * 人工反馈分发器。
 *
 * 这个 Dispatcher 只负责解释 HumanFeedbackNode 写回的 `human_next_node` 状态，
 * 决定工作流是在人工反馈点暂停，还是继续回到 Planner / PlanExecutor。
 *
 * 这里的“暂停”不是特殊节点，而是返回 `END` 让当前图先结束，
 * 然后等外部请求通过 `updateState(...)` 再从保存的 thread 上恢复执行。
 */
public class HumanFeedbackDispatcher implements EdgeAction {

	/**
 * `apply`：执行当前类对外暴露的一步核心操作。
 *
 * 阅读这个方法时，建议同时关注它依赖了什么输入，以及结果最后会被哪一层继续消费。
 */
	@Override
	public String apply(OverAllState state) throws Exception {
		String nextNode = (String) state.value("human_next_node", END);

		// WAIT_FOR_FEEDBACK 表示前端还没提交审批结果。
		// 当前轮图执行需要先停住，等待外部请求带着 feedback 重新进入恢复流程。
		if ("WAIT_FOR_FEEDBACK".equals(nextNode)) {
			return END;
		}

		return nextNode;
	}

}
