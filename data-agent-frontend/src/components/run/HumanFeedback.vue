<!--
 * Copyright 2025 the original author or authors.
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
-->

<template>
  <div class="human-feedback-area">
    <div class="feedback-header">
      <el-icon><ChatDotRound /></el-icon>
      <span>请对智能体生成的计划进行评价</span>
    </div>

    <div class="feedback-input">
      <el-input
        v-model="feedbackInput"
        type="textarea"
        :rows="3"
        placeholder="请输入您的反馈意见（可选）..."
        maxlength="500"
        show-word-limit
      />
    </div>

    <div class="feedback-actions">
      <!-- 通过/拒绝都走同一个处理函数，只是传递不同的 rejectedPlan 标志。 -->
      <el-button type="success" @click="submitFeedback(false)">
        <el-icon><Check /></el-icon>
        通过计划
      </el-button>
      <el-button type="danger" @click="submitFeedback(true)">
        <el-icon><Close /></el-icon>
        不通过计划
      </el-button>
    </div>
  </div>
</template>

<script lang="ts">
  import { ref, defineComponent, PropType } from 'vue';
  import { type GraphRequest } from '../../services/graph';
  import { ChatDotRound, Check, Close } from '@element-plus/icons-vue';

  /**
   * 人工反馈组件。
   *
   * 它是前端接入“人工审核/人工纠偏”能力的最小交互单元：
   * - 展示一个输入框，让用户补充反馈意见。
   * - 提供通过/拒绝两个动作按钮。
   * - 把反馈结果交回父组件，由父组件继续调用后端恢复工作流。
   *
   * 注意这里不直接请求后端。
   * 组件只负责采集交互，真正的恢复执行逻辑由 `handleFeedback` 回调承接，
   * 这样组件本身保持纯展示和纯事件派发，更容易复用和测试。
   */
  export default defineComponent({
    name: 'HumanFeedback',
    components: {
      ChatDotRound,
      Check,
      Close,
    },
    props: {
      request: {
        type: Object as PropType<GraphRequest>,
        required: true,
      },
      handleFeedback: {
        type: Function as PropType<
          (request: GraphRequest, rejectedPlan: boolean, content: string) => Promise<void>
        >,
        required: true,
      },
    },
    setup(props) {
      const feedbackInput = ref('');

      /**
       * 提交人工反馈。
       *
       * `rejectedPlan` 为 `true` 表示否决计划；
       * 为 `false` 表示计划通过、允许继续执行。
       */
      const submitFeedback = (rejectedPlan: boolean) => {
        // 如果用户没有填补充意见，则给一个默认文本，方便后端统一处理。
        const feedbackContent = feedbackInput.value.trim() || 'Accept';
        props.handleFeedback(props.request, rejectedPlan, feedbackContent);

        // 提交后立即清空输入框，避免上一次反馈内容残留到下一次审核。
        feedbackInput.value = '';
      };

      return {
        feedbackInput,
        submitFeedback,
      };
    },
  });
</script>

<style scoped>
  .human-feedback-area {
    background: #f8fbff;
    border: 1px solid #e1f0ff;
    border-radius: 12px;
    padding: 20px;
    margin: 16px 0;
  }

  .feedback-header {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 16px;
    color: #409eff;
    font-size: 16px;
    font-weight: 500;
  }

  .feedback-header .el-icon {
    color: #409eff;
    font-size: 18px;
  }

  .feedback-input {
    margin-bottom: 16px;
  }

  .feedback-actions {
    display: flex;
    gap: 12px;
    justify-content: flex-end;
  }

  @media (max-width: 768px) {
    .feedback-actions {
      flex-direction: column;
    }

    .feedback-actions .el-button {
      width: 100%;
    }
  }
</style>
