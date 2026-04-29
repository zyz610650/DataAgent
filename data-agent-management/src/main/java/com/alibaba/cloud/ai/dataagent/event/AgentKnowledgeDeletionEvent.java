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
package com.alibaba.cloud.ai.dataagent.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.Clock;

@Getter
/**
 * AgentKnowledgeDeletionEvent：领域事件定义。
 *
 * 它用于在智能体知识Deletion相关流程中解耦“事件发布”和“事件处理”，常见于异步任务、清理任务和状态广播。
 * 阅读时建议顺着事件发布方和监听方一起看，最容易理解它在主链路中的位置。
 */
public class AgentKnowledgeDeletionEvent extends ApplicationEvent {

	private final Integer knowledgeId;

	public AgentKnowledgeDeletionEvent(Object source, Integer knowledgeId) {
		super(source, Clock.systemDefaultZone());
		this.knowledgeId = knowledgeId;
	}

}
