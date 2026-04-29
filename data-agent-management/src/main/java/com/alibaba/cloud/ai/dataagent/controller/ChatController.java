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
package com.alibaba.cloud.ai.dataagent.controller;

import com.alibaba.cloud.ai.dataagent.dto.ChatMessageDTO;
import com.alibaba.cloud.ai.dataagent.entity.ChatMessage;
import com.alibaba.cloud.ai.dataagent.entity.ChatSession;
import com.alibaba.cloud.ai.dataagent.service.chat.ChatMessageService;
import com.alibaba.cloud.ai.dataagent.service.chat.ChatSessionService;
import com.alibaba.cloud.ai.dataagent.service.chat.SessionTitleService;
import com.alibaba.cloud.ai.dataagent.util.ReportTemplateUtil;
import com.alibaba.cloud.ai.dataagent.vo.ApiResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话与消息管理接口。
 *
 * 这个 Controller 负责“聊天外围能力”，和 `GraphController` 的职责边界不同：
 * - `GraphController` 负责实时分析与流式执行。
 * - 当前类负责会话、消息、标题、报告下载等持久化管理能力。
 *
 * 推荐阅读这个类的原因：
 * 1. 它能帮助你快速理解系统有哪些“分析之外”的配套数据模型。
 * 2. 它展示了前端如何围绕一次分析任务组织会话生命周期。
 */
@Slf4j
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class ChatController {

	private final ChatSessionService chatSessionService;

	private final ChatMessageService chatMessageService;

	private final SessionTitleService sessionTitleService;

	private final ReportTemplateUtil reportTemplateUtil;

	/**
	 * 查询某个 Agent 的全部会话。
	 *
	 * 框架 API 说明：
	 * - `@PathVariable` 把 URL 中的 `{id}` 绑定为方法参数。
	 * - `ResponseEntity.ok(...)` 是 Spring 构造 200 响应的常见写法，后续也方便附加 Header。
	 */
	@GetMapping("/agent/{id}/sessions")
	public ResponseEntity<List<ChatSession>> getAgentSessions(@PathVariable(value = "id") Integer id) {
		List<ChatSession> sessions = chatSessionService.findByAgentId(id);
		return ResponseEntity.ok(sessions);
	}

	/**
	 * 创建一个新会话。
	 *
	 * 这里允许请求体为空，表示前端可以只传 Agent ID，由后端补默认值。
	 * 这类设计常见于“用户进入页面后先快速落一个空会话，再逐步填充内容”的场景。
	 */
	@PostMapping("/agent/{id}/sessions")
	public ResponseEntity<ChatSession> createSession(@PathVariable(value = "id") Integer id,
			@RequestBody(required = false) Map<String, Object> request) {
		String title = request != null ? (String) request.get("title") : null;
		Long userId = request != null ? (Long) request.get("userId") : null;

		ChatSession session = chatSessionService.createSession(id, title, userId);
		return ResponseEntity.ok(session);
	}

	/**
	 * 清空某个 Agent 下的全部会话。
	 */
	@DeleteMapping("/agent/{id}/sessions")
	public ResponseEntity<ApiResponse> clearAgentSessions(@PathVariable(value = "id") Integer id) {
		chatSessionService.clearSessionsByAgentId(id);
		return ResponseEntity.ok(ApiResponse.success("会话已清空"));
	}

	/**
	 * 查询单个会话的消息列表。
	 */
	@GetMapping("/sessions/{sessionId}/messages")
	public ResponseEntity<List<ChatMessage>> getSessionMessages(@PathVariable(value = "sessionId") String sessionId) {
		List<ChatMessage> messages = chatMessageService.findBySessionId(sessionId);
		return ResponseEntity.ok(messages);
	}

	/**
	 * 保存一条消息到指定会话。
	 *
	 * 除了落库消息本身，这里还会做两个附带动作：
	 * 1. 更新会话最近活跃时间，便于前端按最近对话排序。
	 * 2. 当请求标记 `titleNeeded=true` 时，异步触发标题生成。
	 */
	@PostMapping("/sessions/{sessionId}/messages")
	public ResponseEntity<ChatMessage> saveMessage(@PathVariable(value = "sessionId") String sessionId,
			@RequestBody ChatMessageDTO request) {
		try {
			if (request == null) {
				return ResponseEntity.badRequest().build();
			}
			ChatMessage message = ChatMessage.builder()
				.sessionId(sessionId)
				.role(request.getRole())
				.content(request.getContent())
				.messageType(request.getMessageType())
				.metadata(request.getMetadata())
				.build();

			ChatMessage savedMessage = chatMessageService.saveMessage(message);

			chatSessionService.updateSessionTime(sessionId);

			if (request.isTitleNeeded()) {
				sessionTitleService.scheduleTitleGeneration(sessionId, message.getContent());
			}

			return ResponseEntity.ok(savedMessage);
		}
		catch (Exception e) {
			log.error("Save message error for session {}: {}", sessionId, e.getMessage(), e);
			return ResponseEntity.internalServerError().build();
		}
	}

	/**
	 * 置顶或取消置顶会话。
	 */
	@PutMapping("/sessions/{sessionId}/pin")
	public ResponseEntity<ApiResponse> pinSession(@PathVariable(value = "sessionId") String sessionId,
			@RequestParam(value = "isPinned") Boolean isPinned) {
		try {
			chatSessionService.pinSession(sessionId, isPinned);
			String message = isPinned ? "会话已置顶" : "会话已取消置顶";
			return ResponseEntity.ok(ApiResponse.success(message));
		}
		catch (Exception e) {
			log.error("Pin session error for session {}: {}", sessionId, e.getMessage(), e);
			return ResponseEntity.internalServerError().body(ApiResponse.error("操作失败"));
		}
	}

	/**
	 * 重命名会话。
	 *
	 * `StringUtils.hasText(...)` 是 Spring 常用字符串工具，
	 * 比单纯判断 `null` 更严格，会把空串和全空白串都视为无效输入。
	 */
	@PutMapping("/sessions/{sessionId}/rename")
	public ResponseEntity<ApiResponse> renameSession(@PathVariable(value = "sessionId") String sessionId,
			@RequestParam(value = "title") String title) {
		try {
			if (!StringUtils.hasText(title)) {
				return ResponseEntity.badRequest().body(ApiResponse.error("标题不能为空"));
			}

			chatSessionService.renameSession(sessionId, title.trim());
			return ResponseEntity.ok(ApiResponse.success("会话已重命名"));
		}
		catch (Exception e) {
			log.error("Rename session error for session {}: {}", sessionId, e.getMessage(), e);
			return ResponseEntity.internalServerError().body(ApiResponse.error("重命名失败"));
		}
	}

	/**
	 * 删除单个会话。
	 */
	@DeleteMapping("/sessions/{sessionId}")
	public ResponseEntity<ApiResponse> deleteSession(@PathVariable(value = "sessionId") String sessionId) {
		try {
			chatSessionService.deleteSession(sessionId);
			return ResponseEntity.ok(ApiResponse.success("会话已删除"));
		}
		catch (Exception e) {
			log.error("Delete session error for session {}: {}", sessionId, e.getMessage(), e);
			return ResponseEntity.internalServerError().body(ApiResponse.error("删除失败"));
		}
	}

	/**
	 * 下载 HTML 报告。
	 *
	 * 这个接口不负责“生成报告内容”，只负责把已有内容包装成完整 HTML 并作为附件下载。
	 *
	 * 关键框架 API：
	 * - `ResponseEntity<byte[]>`：适合返回文件下载这类二进制响应。
	 * - `Content-Disposition: attachment`：告诉浏览器这是下载附件而不是普通页面。
	 */
	@PostMapping("/sessions/{sessionId}/reports/html")
	public ResponseEntity<byte[]> convertAndDownloadHtml(@PathVariable(value = "sessionId") String sessionId,
			@RequestBody String content) {
		try {
			if (!StringUtils.hasText(content)) {
				return ResponseEntity.badRequest().build();
			}
			log.debug("Download HTML report for session {}", sessionId);
			StringBuilder htmlContent = new StringBuilder();
			htmlContent.append(reportTemplateUtil.getHeader());
			htmlContent.append(content);
			htmlContent.append(reportTemplateUtil.getFooter());
			String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
			String filename = "report_" + timestamp + ".html";
			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(new MediaType("text", "html", StandardCharsets.UTF_8));
			headers.setContentDispositionFormData("attachment", filename);
			return ResponseEntity.ok().headers(headers).body(htmlContent.toString().getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception e) {
			log.error("Download HTML report error for session {}: {}", sessionId, e.getMessage(), e);
			return ResponseEntity.internalServerError().build();
		}
	}

}
