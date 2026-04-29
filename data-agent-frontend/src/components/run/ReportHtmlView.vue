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
  <div class="report-html-view-wrapper">
    <!--
      使用 iframe 而不是直接把 HTML 挂进当前页面，原因有两个：
      1. 报告模板可能带有独立样式，放在 iframe 内可避免污染主页面样式。
      2. 配合 sandbox 可以限制执行能力，降低富文本报告带来的安全风险。
    -->
    <iframe
      ref="iframeRef"
      class="report-html-iframe"
      sandbox="allow-scripts"
      title="HTML 报告预览"
    />
  </div>
</template>

<script lang="ts">
  import { defineComponent, ref, watch, nextTick } from 'vue';
  import { buildReportHtml } from './charts/report-html-template';

  /**
   * HTML 报告预览组件。
   *
   * 后端最终生成的报告主体通常是 Markdown 或中间格式文本，
   * `buildReportHtml(...)` 会把它包进统一的 HTML 模板，再通过 iframe 做隔离预览。
   */
  export default defineComponent({
    name: 'ReportHtmlView',
    props: {
      content: {
        type: String,
        default: '',
      },
    },
    setup(props) {
      const iframeRef = ref<HTMLIFrameElement | null>(null);

      /**
       * 把当前报告内容加载进 iframe。
       *
       * 这里使用 `Blob + URL.createObjectURL(...)` 而不是直接写 `srcdoc`，
       * 主要是为了让完整 HTML 资源以独立 URL 的方式加载，并在 load 后手动释放 URL。
       */
      const loadHtml = () => {
        if (!iframeRef.value) return;

        if (!props.content) {
          iframeRef.value.srcdoc =
            '<html><body style="padding:20px;color:#666;">暂无报告内容</body></html>';
          return;
        }

        const html = buildReportHtml(props.content);
        const blob = new Blob([html], { type: 'text/html;charset=utf-8' });
        const url = URL.createObjectURL(blob);

        const iframe = iframeRef.value;
        const onLoad = () => {
          URL.revokeObjectURL(url);
          iframe.removeEventListener('load', onLoad);
        };
        iframe.addEventListener('load', onLoad);
        iframe.src = url;
      };

      // 监听 content 变化，确保每次后端返回新报告后都重新渲染预览。
      watch(
        () => props.content,
        () => {
          nextTick(loadHtml);
        },
        { immediate: true },
      );

      return {
        iframeRef,
      };
    },
  });
</script>

<style scoped>
  .report-html-view-wrapper {
    width: 100%;
    min-height: 400px;
    border: 1px solid #e2e8f0;
    border-radius: 8px;
    overflow: hidden;
    background: #fff;
  }

  .report-html-iframe {
    width: 100%;
    min-height: 600px;
    border: none;
    display: block;
  }
</style>
