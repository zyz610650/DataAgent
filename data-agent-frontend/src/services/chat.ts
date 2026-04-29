/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import axios from 'axios';
import type { ApiResponse } from './common';

/**
 * 会话数据模型。
 *
 * 这个结构对应后端 `ChatSession` 实体，是前端会话列表、当前会话切换和侧边栏展示的基础对象。
 */
export interface ChatSession {
  id: string;
  agentId: number;
  title: string;
  status: string;
  isPinned: boolean;
  userId?: number;
  createTime?: Date;
  updateTime?: Date;
}

/**
 * 消息数据模型。
 *
 * `messageType` 不是装饰字段，而是决定前端渲染分支的关键输入：
 * - `text`：普通文本
 * - `html`：直接 HTML 渲染
 * - `result-set`：表格结果集
 * - `markdown-report`：报告展示
 */
export interface ChatMessage {
  id?: number;
  sessionId: string;
  role: string;
  content: string;
  messageType: string;
  metadata?: string;
  createTime?: Date;
  titleNeeded?: boolean;
}

const API_BASE_URL = '/api';

/**
 * 会话与消息 API 服务。
 *
 * 这个服务专门负责和后端 `ChatController` 通信，职责边界和 `GraphService` 不同：
 * - `GraphService` 处理实时流式分析。
 * - 当前服务处理会话和消息的持久化管理。
 *
 * 这里统一使用 `axios`，因为这些接口都是标准请求/响应模式，不需要 SSE。
 */
class ChatService {
  /**
   * 获取某个 Agent 下的全部会话。
   */
  async getAgentSessions(agentId: number): Promise<ChatSession[]> {
    const response = await axios.get<ChatSession[]>(`${API_BASE_URL}/agent/${agentId}/sessions`);
    return response.data;
  }

  /**
   * 创建新会话。
   *
   * `title` 和 `userId` 都是可选的，便于前端先快速创建会话，再逐步补全信息。
   */
  async createSession(agentId: number, title?: string, userId?: number): Promise<ChatSession> {
    const request = {
      title,
      userId,
    };

    const response = await axios.post<ChatSession>(
      `${API_BASE_URL}/agent/${agentId}/sessions`,
      request,
    );
    return response.data;
  }

  /**
   * 清空某个 Agent 的全部会话。
   */
  async clearAgentSessions(agentId: number): Promise<ApiResponse> {
    const response = await axios.delete<ApiResponse>(`${API_BASE_URL}/agent/${agentId}/sessions`);
    return response.data;
  }

  /**
   * 获取单个会话的消息历史。
   */
  async getSessionMessages(sessionId: string): Promise<ChatMessage[]> {
    const response = await axios.get<ChatMessage[]>(
      `${API_BASE_URL}/sessions/${sessionId}/messages`,
    );
    return response.data;
  }

  /**
   * 向会话中保存一条消息。
   *
   * 这里会再次显式写入 `sessionId`，是为了保证调用方即便传入了不完整 message，也不会丢失归属关系。
   */
  async saveMessage(sessionId: string, message: ChatMessage): Promise<ChatMessage> {
    try {
      const messageData = {
        ...message,
        sessionId,
      };

      const response = await axios.post<ChatMessage>(
        `${API_BASE_URL}/sessions/${sessionId}/messages`,
        messageData,
      );
      return response.data;
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.status === 500) {
        throw new Error('保存消息失败');
      }
      throw error;
    }
  }

  /**
   * 置顶或取消置顶会话。
   */
  async pinSession(sessionId: string, isPinned: boolean): Promise<ApiResponse> {
    try {
      const response = await axios.put<ApiResponse>(
        `${API_BASE_URL}/sessions/${sessionId}/pin`,
        null,
        {
          params: { isPinned },
        },
      );
      return response.data;
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.status === 400) {
        throw new Error('isPinned 参数不能为空');
      }
      if (axios.isAxiosError(error) && error.response?.status === 500) {
        throw new Error('操作失败');
      }
      throw error;
    }
  }

  /**
   * 重命名会话。
   *
   * 前端先做一层空值校验，减少无意义请求；
   * 后端仍会保留自己的校验逻辑，避免仅依赖前端约束。
   */
  async renameSession(sessionId: string, title: string): Promise<ApiResponse> {
    try {
      if (!title || title.trim().length === 0) {
        throw new Error('标题不能为空');
      }

      const response = await axios.put<ApiResponse>(
        `${API_BASE_URL}/sessions/${sessionId}/rename`,
        null,
        {
          params: { title: title.trim() },
        },
      );
      return response.data;
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.status === 400) {
        throw new Error('标题不能为空');
      }
      if (axios.isAxiosError(error) && error.response?.status === 500) {
        throw new Error('重命名失败');
      }
      throw error;
    }
  }

  /**
   * 删除单个会话。
   */
  async deleteSession(sessionId: string): Promise<ApiResponse> {
    try {
      const response = await axios.delete<ApiResponse>(`${API_BASE_URL}/sessions/${sessionId}`);
      return response.data;
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.status === 500) {
        throw new Error('删除失败');
      }
      throw error;
    }
  }

  /**
   * 请求后端将 Markdown 报告包装成 HTML 文件并下载。
   *
   * 这里没有在前端自己拼装 Blob HTML 模板，而是复用后端统一报告模板，
   * 这样前后端生成出的最终下载结果更一致。
   */
  async downloadHtmlReport(sessionId: string, content: string): Promise<void> {
    try {
      const response = await axios.post(
        `${API_BASE_URL}/sessions/${sessionId}/reports/html`,
        content,
        {
          responseType: 'blob',
          headers: {
            'Content-Type': 'text/plain;charset=utf-8',
          },
        },
      );

      const contentDisposition = response.headers['content-disposition'];
      let filename = 'report.html';
      if (contentDisposition) {
        const filenameMatch = contentDisposition.match(/filename="?([^;"]+)"?/);
        if (filenameMatch && filenameMatch[1]) {
          filename = filenameMatch[1];
        }
      }

      const blob = new Blob([response.data], { type: 'text/html' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
    } catch (error) {
      if (axios.isAxiosError(error)) {
        throw new Error(`下载失败: ${error.message}`);
      }
      throw error;
    }
  }
}

export default new ChatService();
