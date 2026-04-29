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
package com.alibaba.cloud.ai.dataagent.workflow.node;

import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.HUMAN_FEEDBACK_DATA;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.HUMAN_REVIEW_ENABLED;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLAN_CURRENT_STEP;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLAN_EXECUTOR_NODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLAN_REPAIR_COUNT;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLAN_VALIDATION_ERROR;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLANNER_NODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.PLANNER_NODE_OUTPUT;

/**
 * 人工反馈节点。
 *
 * 这是整条 Graph 中最关键的“人工介入点”之一。
 * 当系统启用了人工复核后，流程会在这里暂停，等待前端或外部系统把人工审批结果写回状态。
 *
 * 该节点本身不做复杂业务，只做三件事：
 * 1. 判断是否已经拿到人工反馈。
 * 2. 反馈通过则恢复执行计划。
 * 3. 反馈拒绝则回到 Planner 重新生成计划。
 */
@Slf4j
@Component
public class HumanFeedbackNode implements NodeAction {

	/**
	 * 处理人工反馈结果。
	 *
	 * 关键状态：
	 * - `HUMAN_FEEDBACK_DATA`：前端或外部系统写入的审批结果。
	 * - `human_next_node`：当前节点最终告诉 Dispatcher 的下一跳。
	 */
	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		Map<String, Object> updated = new HashMap<>();

		int repairCount = StateUtil.getObjectValue(state, PLAN_REPAIR_COUNT, Integer.class, 0);
		if (repairCount >= 3) {
			log.warn("Max repair attempts (3) exceeded, ending process");
			updated.put("human_next_node", "END");
			return updated;
		}

		@SuppressWarnings("unchecked")
		Map<String, Object> feedbackData = StateUtil.getObjectValue(state, HUMAN_FEEDBACK_DATA, Map.class, Map.of());
		if (feedbackData.isEmpty()) {
			// 还没拿到人工反馈时，不继续执行，而是告诉 Dispatcher 进入等待态。
			updated.put("human_next_node", "WAIT_FOR_FEEDBACK");
			return updated;
		}

		Object approvedValue = feedbackData.getOrDefault("feedback", true);
		boolean approved = approvedValue instanceof Boolean approvedBoolean ? approvedBoolean
				: Boolean.parseBoolean(approvedValue.toString());

		if (approved) {
			log.info("Plan approved -> execution");
			updated.put("human_next_node", PLAN_EXECUTOR_NODE);
			updated.put(HUMAN_REVIEW_ENABLED, false);
		}
		else {
			log.info("Plan rejected -> regeneration (attempt {})", repairCount + 1);
			updated.put("human_next_node", PLANNER_NODE);
			updated.put(PLAN_REPAIR_COUNT, repairCount + 1);
			updated.put(PLAN_CURRENT_STEP, 1);
			updated.put(HUMAN_REVIEW_ENABLED, true);

			String feedbackContent = feedbackData.getOrDefault("feedback_content", "").toString();
			updated.put(PLAN_VALIDATION_ERROR,
					StringUtils.hasLength(feedbackContent) ? feedbackContent : "Plan rejected by user");

			// 计划被否决时，清空旧计划输出，避免下一轮 Planner 修复误用老结果。
			updated.put(PLANNER_NODE_OUTPUT, "");
		}

		return updated;
	}

}
