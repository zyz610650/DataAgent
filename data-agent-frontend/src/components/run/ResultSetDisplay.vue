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

<script setup lang="ts">
  import { ref, computed, nextTick } from 'vue';
  import ChartComponent from './ChartComponent.vue';
  import type { ResultData } from '@/services/resultSet';
  import {
    Grid as ICON_TABLE,
    Histogram as ICON_CHART,
    CopyDocument as ICON_COPY,
  } from '@element-plus/icons-vue';
  import { ElMessage } from 'element-plus';

  /**
   * 结果集展示组件。
   *
   * 后端 `SqlExecuteNode` 返回的结果不仅有结果集本身，还有一份 `displayStyle`，
   * 用来告诉前端“更适合以表格还是图表方式展示”。
   *
   * 当前组件就是这份结果的统一展示入口：
   * - 有图表配置时，支持图表 / 表格双视图切换。
   * - 无图表配置时，退化为纯表格展示。
   * - 额外提供复制 JSON 数据能力，方便调试和二次使用。
   */
  const props = defineProps<{
    resultData: ResultData;
    pageSize: number;
  }>();

  // 当前是否处于图表视图。默认值会在 nextTick 后按 showChart 结果自动校正。
  const isChartView = ref(true);

  /**
   * 判断当前结果是否适合显示图表。
   *
   * 规则来自后端 `displayStyle.type`：
   * - 没有 type 或 type 为 `table` 时，只显示表格。
   * - 其他类型表示后端推荐图表展示。
   */
  const showChart = computed(() => {
    return (
      props.resultData &&
      props.resultData.displayStyle?.type &&
      props.resultData.displayStyle?.type !== 'table'
    );
  });

  /**
   * 生成纯表格 HTML。
   *
   * 这里选择手工拼接 HTML 而不是直接用 `el-table`，主要是为了：
   * - 让结果块能被统一序列化进消息流展示区域。
   * - 更容易和现有的富文本 / Markdown 渲染链路兼容。
   */
  const generateTableHtml = (): string => {
    const resultSet = props.resultData?.resultSet || {};
    const columns = resultSet.column || [];
    const allData = resultSet.data || [];
    const total = allData.length;
    const pageSize = props.pageSize;
    const totalPages = Math.ceil(total / pageSize);

    let tableHtml = `<div class="result-set-container"><div class="result-set-header"><div class="result-set-info"><span>查询结果 (共 ${total} 条记录)</span><div class="result-set-pagination-controls"><span class="result-set-pagination-info">第 <span class="result-set-current-page">1</span> 页，共 ${totalPages} 页</span><div class="result-set-pagination-buttons"><button class="result-set-pagination-btn result-set-pagination-prev" onclick="handleResultSetPagination(this, 'prev')" disabled>上一页</button><button class="result-set-pagination-btn result-set-pagination-next" onclick="handleResultSetPagination(this, 'next')" ${totalPages > 1 ? '' : 'disabled'}>下一页</button></div></div></div></div><div class="result-set-table-container">`;

    for (let page = 1; page <= totalPages; page++) {
      const startIndex = (page - 1) * pageSize;
      const endIndex = Math.min(startIndex + pageSize, total);
      const currentPageData = allData.slice(startIndex, endIndex);

      tableHtml += `<div class="result-set-page ${page === 1 ? 'result-set-page-active' : ''}" data-page="${page}"><table class="result-set-table"><thead><tr>`;

      columns.forEach(column => {
        tableHtml += `<th>${escapeHtml(column)}</th>`;
      });

      tableHtml += `</tr></thead><tbody>`;

      if (currentPageData.length === 0) {
        tableHtml += `<tr><td colspan="${columns.length}" class="result-set-empty-cell">暂无数据</td></tr>`;
      } else {
        currentPageData.forEach(row => {
          tableHtml += `<tr>`;
          columns.forEach(column => {
            const value = row[column] || '';
            tableHtml += `<td>${escapeHtml(value)}</td>`;
          });
          tableHtml += `</tr>`;
        });
      }

      tableHtml += `</tbody></table></div>`;
    }

    tableHtml += `</div></div>`;
    return tableHtml;
  };

  /**
   * HTML 转义，避免结果集中的文本直接注入 DOM。
   *
   * 这是前端展示数据库内容时很重要的一层保护，防止结果数据中带有恶意 HTML 片段。
   */
  const escapeHtml = (text: string): string => {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
  };

  const switchToChart = () => {
    isChartView.value = true;
  };

  const switchToTable = () => {
    isChartView.value = false;
  };

  /**
   * 复制原始 JSON 结果。
   *
   * 这对开发调试很有帮助，尤其是验证后端返回结构、排查图表展示异常时。
   */
  const copyJsonData = () => {
    try {
      const data = props.resultData?.resultSet?.data || [];
      const jsonData = JSON.stringify(data, null, 2);
      navigator.clipboard
        .writeText(jsonData)
        .then(() => {
          ElMessage.success('数据已复制到剪贴板');
        })
        .catch(err => {
          console.error('复制失败:', err);
          ElMessage.error('复制失败');
        });
    } catch (err) {
      console.error('JSON 转换失败:', err);
      ElMessage.error('复制失败');
    }
  };

  // 组件挂载后的初始视图由后端 displayStyle 决定。
  nextTick(() => {
    isChartView.value = showChart.value;
  });
</script>

<template>
  <div
    v-if="resultData && resultData.resultSet && resultData.resultSet.errorMsg"
    class="result-set-error"
  >
    错误: {{ resultData.resultSet.errorMsg }}
  </div>
  <div
    v-else-if="
      !resultData ||
      !resultData.resultSet ||
      !resultData.resultSet.column ||
      resultData.resultSet.column.length === 0 ||
      !resultData.resultSet.data ||
      resultData.resultSet.data.length === 0
    "
    class="result-set-empty"
  >
    查询结果为空
  </div>
  <div v-else class="agent-response-block">
    <div class="agent-response-title result-set-header-bar">
      <div class="agent-response-title">
        {{ resultData.displayStyle?.title || '查询结果' }}
      </div>

      <div v-if="showChart" class="buttons-bar">
        <div class="chart-select-container">
          <el-tooltip effect="dark" content="图表" placement="top">
            <el-button
              class="tool-btn"
              :class="{ 'view-active': isChartView }"
              text
              @click="switchToChart"
            >
              <el-icon size="16">
                <ICON_CHART />
              </el-icon>
            </el-button>
          </el-tooltip>

          <el-tooltip effect="dark" content="表格" placement="top">
            <el-button
              class="tool-btn"
              :class="{ 'view-active': !isChartView }"
              text
              @click="switchToTable"
            >
              <el-icon size="16">
                <ICON_TABLE />
              </el-icon>
            </el-button>
          </el-tooltip>

          <el-tooltip effect="dark" content="复制 JSON" placement="top">
            <el-button class="tool-btn" text @click="copyJsonData">
              <el-icon size="16">
                <ICON_COPY />
              </el-icon>
            </el-button>
          </el-tooltip>
        </div>
      </div>
    </div>

    <div class="result-show-area">
      <ChartComponent v-if="isChartView && showChart" :resultData="resultData" />
      <div v-else v-html="generateTableHtml()"></div>
    </div>
  </div>
</template>

<style scoped>
  .result-set-error {
    padding: 12px;
    background-color: #fef0f0;
    border: 1px solid #fbc4c4;
    border-radius: 4px;
    color: #f56c6c;
    margin: 8px 0;
  }

  .result-set-empty {
    padding: 12px;
    background-color: #f4f4f5;
    border: 1px solid #e9e9eb;
    border-radius: 4px;
    color: #909399;
    margin: 8px 0;
  }

  .result-set-header-bar {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 12px;
    padding: 8px 0;
    border-bottom: 1px solid #ebeef5;
  }

  .buttons-bar {
    display: flex;
    align-items: center;
  }

  .chart-select-container {
    display: flex;
    align-items: center;
  }

  .tool-btn {
    padding: 4px 8px;
    margin-left: 4px;
    border-radius: 4px;
  }

  .tool-btn:hover {
    background-color: #f5f7fa;
  }

  .view-active {
    background-color: #ecf5ff;
    color: #409eff;
  }

  .result-show-area {
    width: 100%;
    min-height: 300px;
  }
</style>
