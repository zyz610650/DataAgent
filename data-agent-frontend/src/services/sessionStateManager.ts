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

import { ref } from 'vue';
import type { Ref } from 'vue';
import { GraphNodeResponse, GraphRequest } from '@/services/graph.ts';

/**
 * 单个会话在“运行中”阶段的前端运行态。
 *
 * 这里刻意和后端 `ChatSession` 持久化数据分开：
 * - `ChatSession` 表示数据库中保存的会话信息
 * - `SessionRuntimeState` 表示前端页面运行期间的临时状态
 *
 * 这样做的意义是：
 * 1. 页面切换会话时，能保留每个会话独立的流式运行现场。
 * 2. 不需要把这些临时态都写回数据库。
 */
export interface SessionRuntimeState {
  isStreaming: boolean;
  nodeBlocks: GraphNodeResponse[][];
  closeStream: (() => void) | null;
  lastRequest: GraphRequest | null;
  htmlReportContent: string;
  htmlReportSize: number;
  markdownReportContent: string;
}

/**
 * 会话运行态管理器。
 *
 * 这是 `AgentRun.vue` 很关键的配套模块，用来解决“多个会话之间的流式状态隔离”问题。
 * 如果没有这一层，用户在侧边栏来回切换会话时，当前页面上的运行状态会互相污染。
 *
 * 返回的是一个组合式函数（Composable）：
 * - 好处是可以在多个组件中复用逻辑
 * - 同时又保留 Vue 响应式能力
 */
export function useSessionStateManager() {
  /**
   * 用 Map 而不是普通对象，是为了让 `sessionId -> 运行态` 的关系更明确，
   * 也更适合做动态增删。
   */
  const sessionStates = ref<Map<string, SessionRuntimeState>>(new Map());

  /**
   * 获取指定会话的运行态。
   *
   * 如果当前会话还没有运行态记录，会按默认值先初始化一份。
   * 这样调用方不需要在每次使用前重复做“是否存在”的判空分支。
   */
  const getSessionState = (sessionId: string): SessionRuntimeState => {
    if (!sessionStates.value.has(sessionId)) {
      sessionStates.value.set(sessionId, {
        isStreaming: false,
        nodeBlocks: [],
        closeStream: null,
        lastRequest: null,
        htmlReportContent: '',
        htmlReportSize: 0,
        markdownReportContent: '',
      });
    }
    return sessionStates.value.get(sessionId)!;
  };

  /**
   * 把缓存中的会话运行态同步到当前页面视图状态。
   *
   * 典型场景：
   * - 用户从会话 A 切到会话 B
   * - 需要把 B 之前保留下来的 `isStreaming/nodeBlocks` 恢复到页面
   */
  const syncStateToView = (
    sessionId: string,
    viewState: {
      isStreaming: Ref<boolean>;
      nodeBlocks: Ref<GraphNodeResponse[][]>;
    },
  ) => {
    const state = getSessionState(sessionId);
    viewState.isStreaming.value = state.isStreaming;
    viewState.nodeBlocks.value = state.nodeBlocks;
  };

  /**
   * 把页面当前状态写回会话运行态缓存。
   *
   * 这样用户切走再切回来时，可以继续看到之前那一刻的流式输出现场。
   */
  const saveViewToState = (
    sessionId: string,
    viewState: {
      isStreaming: Ref<boolean>;
      nodeBlocks: Ref<GraphNodeResponse[][]>;
    },
  ) => {
    const state = getSessionState(sessionId);
    state.isStreaming = viewState.isStreaming.value;
    state.nodeBlocks = viewState.nodeBlocks.value;
  };

  /**
   * 删除某个会话的运行态缓存。
   *
   * 如果这个会话还有活跃的流连接，会先主动调用 `closeStream()` 做清理，
   * 避免用户删掉会话后后台 SSE 还在继续推数据。
   */
  const deleteSessionState = (sessionId: string) => {
    const state = sessionStates.value.get(sessionId);
    if (state?.closeStream) {
      state.closeStream();
    }
    sessionStates.value.delete(sessionId);
  };

  /**
   * 获取当前仍在运行中的会话 ID 列表。
   *
   * 这类能力通常用于：
   * - 页面卸载前清理
   * - 侧边栏显示运行态标识
   * - 调试时快速定位哪些会话仍有活跃流
   */
  const getRunningSessionIds = (): string[] => {
    const runningIds: string[] = [];
    sessionStates.value.forEach((state, sessionId) => {
      if (state.isStreaming) {
        runningIds.push(sessionId);
      }
    });
    return runningIds;
  };

  return {
    sessionStates,
    getSessionState,
    syncStateToView,
    saveViewToState,
    deleteSessionState,
    getRunningSessionIds,
  };
}
