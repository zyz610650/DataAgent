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

import { createRouter, createWebHistory } from 'vue-router';
import { ElMessage } from 'element-plus';
import routes from '@/router/routes';
import modelConfigService from '@/services/modelConfig';

/**
 * 创建前端路由实例。
 *
 * 这个文件不只是“把 routes 导进来”这么简单，它还承担了两个很重要的横切职责：
 * 1. 切换页面时统一设置浏览器标题。
 * 2. 在进入大部分页面前检查模型配置是否就绪，避免用户直接进入运行页后才发现后端没有可用模型。
 *
 * 关键框架 API：
 * - `createWebHistory()`：
 *   使用 HTML5 History 模式，URL 更干净，但部署时需要服务端支持 history fallback。
 * - `router.beforeEach(...)`：
 *   全局前置守卫，适合做鉴权、初始化检查、路由拦截。
 * - `router.afterEach(...)`：
 *   全局后置钩子，适合做埋点、日志和路由完成后的统一处理。
 */
const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior(to, from, savedPosition) {
    // 使用浏览器前进后退时优先恢复原滚动位置；
    // 普通路由跳转则回到页面顶部，避免用户带着上一页滚动位置进入新页面。
    if (savedPosition) {
      return savedPosition;
    }
    return { top: 0 };
  },
});

/**
 * 记录“模型未配置”提示是否已经显示过，避免用户在一次连续跳转中被重复弹窗打断。
 */
let hasShownWarning = false;

/**
 * 全局前置守卫。
 *
 * 当前路由策略很直接：
 * 1. `/model-config` 永远放行，因为它本身就是“修复模型配置”的页面。
 * 2. 其他核心页面进入前，都先调用后端检查模型是否 ready。
 * 3. 如果未就绪，则统一重定向到模型配置页。
 */
router.beforeEach(async (to, from, next) => {
  if (to.meta?.title) {
    document.title = `${to.meta.title} - Spring AI Alibaba Data Agent`;
  } else {
    document.title = 'Spring AI Alibaba Data Agent';
  }

  if (to.path === '/model-config') {
    console.log(`导航到 ${to.path} (${to.name})`);
    next();
    return;
  }

  try {
    const result = await modelConfigService.checkReady();

    if (!result.ready) {
      const missingModels = [];
      if (!result.chatModelReady) {
        missingModels.push('聊天模型');
      }
      if (!result.embeddingModelReady) {
        missingModels.push('嵌入模型');
      }

      if (!hasShownWarning) {
        ElMessage.warning({
          message: `欢迎使用。检测到您尚未配置 ${missingModels.join(' 和 ')}，请先完成模型配置后再使用运行能力。`,
          duration: 5000,
        });
        hasShownWarning = true;
      }

      console.log('模型配置未就绪，重定向到配置页面');
      next('/model-config');
      return;
    }

    hasShownWarning = false;
    console.log(`导航到 ${to.path} (${to.name})`);
    next();
  } catch (error) {
    console.error('检查模型配置失败', error);

    if (!hasShownWarning) {
      ElMessage.error({
        message: '无法检查模型配置状态，请确认后端服务已启动并且模型配置接口可用。',
        duration: 5000,
      });
      hasShownWarning = true;
    }

    next('/model-config');
  }
});

router.afterEach((to, from) => {
  console.log(`导航完成: ${from.path} -> ${to.path}`);
});

export default router;
