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

/**
 * 运行主链路请求参数。
 *
 * 这个结构和后端 `GraphRequest` 基本一一对应，
 * 前端通过它告诉后端本次是普通分析、纯 NL2SQL，还是带人工反馈的续跑。
 */
export interface GraphRequest {
  agentId: string;
  threadId?: string;
  query: string;
  humanFeedback: boolean;
  humanFeedbackContent?: string;
  rejectedPlan: boolean;
  nl2sqlOnly: boolean;
}

/**
 * 后端每个流式节点推回来的消息结构。
 *
 * 它是前端展示“当前执行到哪个节点、节点吐了什么内容”的最小事件模型。
 */
export interface GraphNodeResponse {
  agentId: string;
  threadId: string;
  nodeName: string;
  textType: TextType;
  text: string;
  error: boolean;
  complete: boolean;
}

/**
 * 后端流式输出文本类型枚举。
 *
 * 前端会基于这个字段决定如何渲染内容：
 * - `SQL` 可能走代码高亮
 * - `RESULT_SET` 走表格展示
 * - `MARK_DOWN` / `HTML` 走报告视图
 */
export enum TextType {
  JSON = 'JSON',
  PYTHON = 'PYTHON',
  SQL = 'SQL',
  HTML = 'HTML',
  MARK_DOWN = 'MARK_DOWN',
  RESULT_SET = 'RESULT_SET',
  TEXT = 'TEXT',
}

const API_BASE_URL = '/api';

/**
 * Graph 运行服务。
 *
 * 这是前端和后端流式分析接口之间的最薄适配层。
 * 它不维护页面状态，也不处理组件渲染，只负责：
 * 1. 组装查询参数
 * 2. 建立 `EventSource` 长连接
 * 3. 把后端 SSE 事件交给外部回调处理
 *
 * 关键浏览器 API：
 * - `EventSource`：
 *   浏览器原生 SSE 客户端，适合接收 `text/event-stream`。
 *   优点是简单，缺点是只能发 GET 请求，所以这里把请求参数都编码到 URL 上。
 */
class GraphService {
  /**
   * 发起一条流式分析请求。
   *
   * 入参中的三个回调分别对应：
   * - `onMessage`：收到普通节点输出
   * - `onError`：连接或解析失败
   * - `onComplete`：后端显式发送 `complete` 事件
   *
   * 返回值是一个关闭函数，调用方可以在用户点击“停止”时主动关闭 EventSource。
   */
  async streamSearch(
    request: GraphRequest,
    onMessage: (response: GraphNodeResponse) => Promise<void>,
    onError?: (error: Error) => Promise<void>,
    onComplete?: () => Promise<void>,
  ): Promise<() => void> {
    const params = new URLSearchParams();
    params.append('agentId', request.agentId);
    if (request.threadId) {
      params.append('threadId', request.threadId);
    }
    params.append('query', request.query);
    params.append('humanFeedback', request.humanFeedback.toString());
    params.append('rejectedPlan', request.rejectedPlan.toString());
    params.append('nl2sqlOnly', request.nl2sqlOnly.toString());

    if (request.humanFeedbackContent) {
      params.append('humanFeedbackContent', request.humanFeedbackContent);
    }

    const url = `${API_BASE_URL}/stream/search?${params.toString()}`;
    const eventSource = new EventSource(url);

    eventSource.onmessage = async event => {
      try {
        const nodeResponse: GraphNodeResponse = JSON.parse(event.data);
        console.log(
          `Node: ${nodeResponse.nodeName}, message: ${nodeResponse.text}, type: ${nodeResponse.textType}`,
        );
        await onMessage(nodeResponse);
      } catch (parseError) {
        console.error('Failed to parse SSE data:', parseError);
        if (onError) {
          await onError(new Error('Failed to parse server response'));
        }
      }
    };

    /**
     * 标记是否已经收到后端的 `complete` 事件。
     *
     * 原因：
     * 某些浏览器在服务端正常关闭 SSE 后，仍然会触发一次 `onerror`。
     * 如果不做这个标记，前端会把正常结束误判成连接失败。
     */
    let isCompleted = false;

    eventSource.onerror = async error => {
      if (isCompleted) {
        return;
      }
      console.error('EventSource error:', error);
      if (onError) {
        await onError(new Error('Stream connection failed'));
      }
      eventSource.close();
    };

    eventSource.addEventListener('complete', async () => {
      isCompleted = true;
      if (onComplete) {
        await onComplete();
      }
      eventSource.close();
    });

    return () => {
      eventSource.close();
    };
  }
}

export default new GraphService();
