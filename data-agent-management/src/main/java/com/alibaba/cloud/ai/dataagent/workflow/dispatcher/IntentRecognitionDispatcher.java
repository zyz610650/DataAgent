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

import com.alibaba.cloud.ai.dataagent.dto.prompt.IntentRecognitionOutputDTO;
import com.alibaba.cloud.ai.dataagent.util.StateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import lombok.extern.slf4j.Slf4j;

import static com.alibaba.cloud.ai.dataagent.constant.Constant.EVIDENCE_RECALL_NODE;
import static com.alibaba.cloud.ai.dataagent.constant.Constant.INTENT_RECOGNITION_NODE_OUTPUT;
import static com.alibaba.cloud.ai.graph.StateGraph.END;

/**
 * 意图识别分发器。
 *
 * `EdgeAction` 是 Graph 框架中的“条件边”接口，返回值就是下一跳节点名。
 * 当前类只做一件事：读取意图识别结果，然后决定主链路是否继续。
 *
 * 分层价值：
 * - Node 负责执行识别。
 * - Dispatcher 负责解释识别结果并做路由。
 * 这样节点逻辑和分支逻辑不会混在一起。
 */
@Slf4j
public class IntentRecognitionDispatcher implements EdgeAction {

	/**
	 * 根据意图识别结果决定下一跳。
	 *
	 * 如果模型把输入识别为闲聊或无关指令，则直接结束流程；
	 * 否则继续进入证据召回阶段。
	 */
	@Override
	public String apply(OverAllState state) throws Exception {
		IntentRecognitionOutputDTO intentResult = StateUtil.getObjectValue(state, INTENT_RECOGNITION_NODE_OUTPUT,
				IntentRecognitionOutputDTO.class);

		if (intentResult == null || intentResult.getClassification() == null
				|| intentResult.getClassification().trim().isEmpty()) {
			log.warn("Intent recognition result is null or empty, defaulting to END");
			return END;
		}

		String classification = intentResult.getClassification();

		// 这里依赖提示词输出协议：识别结果为“《闲聊或无关指令》”时直接结束分析链路。
		if ("《闲聊或无关指令》".equals(classification)) {
			log.warn("Intent classified as chat or irrelevant, ending conversation");
			return END;
		}
		else {
			log.info("Intent classified as potential data analysis request, proceeding to evidence recall");
			return EVIDENCE_RECALL_NODE;
		}
	}

}
