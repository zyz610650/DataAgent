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

import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import lombok.extern.slf4j.Slf4j;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLAN_NEXT_NODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLAN_REPAIR_COUNT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLAN_VALIDATION_STATUS;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLANNER_NODE;
import static com.alibaba.cloud.ai.graph.StateGraph.END;

/**
 * 计划执行分发器。
 *
 * 这个类不负责真正执行计划，它只做一件事：
 * 根据 `PlanExecutorNode` 已经写回到状态里的验证结果，决定工作流下一跳应该去哪里。
 *
 * 在整条 Graph 主链路中，它的角色类似“路口红绿灯”：
 * - 计划校验通过：进入下一执行节点
 * - 计划校验失败但还有修复次数：回到 `PlannerNode`
 * - 修复次数超限：直接结束
 *
 * 关键框架 API：
 * - `EdgeAction`：
 *   Graph 框架里“条件边”的执行接口，返回值就是下一跳节点名。
 */
@Slf4j
public class PlanExecutorDispatcher implements EdgeAction {

	/**
	 * 允许的最大计划修复次数。
	 *
	 * 这里不是 SQL 重试次数，而是“规划本身验证失败后重新修计划”的上限。
	 */
	private static final int MAX_REPAIR_ATTEMPTS = 2;

	/**
	 * 根据计划验证结果决定下一跳。
	 *
	 * 状态来源：
	 * - `PLAN_VALIDATION_STATUS`：当前计划是否验证通过
	 * - `PLAN_NEXT_NODE`：验证通过后，真正应该去的执行节点
	 * - `PLAN_REPAIR_COUNT`：当前已经发生过多少次“退回重做计划”
	 *
	 * 学习要点：
	 * 1. Dispatcher 只读状态，不直接调用服务层。
	 * 2. 节点负责写状态，Dispatcher 负责解释状态并选路。
	 * 3. 这类拆分能让“业务执行”和“路由决策”分层更清晰。
	 */
	@Override
	public String apply(OverAllState state) {
		boolean validationPassed = StateUtil.getObjectValue(state, PLAN_VALIDATION_STATUS, Boolean.class, false);

		if (validationPassed) {
			log.info("Plan validation passed. Proceeding to next step.");
			String nextNode = state.value(PLAN_NEXT_NODE, END);

			// Graph 框架里的终止节点是常量 `END`。
			// 这里统一把字符串形式的 END 转回框架常量，避免后续分支判断歧义。
			if ("END".equals(nextNode)) {
				log.info("Plan execution completed successfully.");
				return END;
			}
			return nextNode;
		}

		int repairCount = StateUtil.getObjectValue(state, PLAN_REPAIR_COUNT, Integer.class, 0);

		if (repairCount > MAX_REPAIR_ATTEMPTS) {
			log.error("Plan repair attempts exceeded the limit of {}. Terminating execution.", MAX_REPAIR_ATTEMPTS);
			return END;
		}

		log.warn("Plan validation failed. Routing back to PlannerNode for repair. Attempt count from state: {}.",
				repairCount);
		return PLANNER_NODE;
	}

}
