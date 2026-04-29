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
import { createApp } from 'vue';
import App from '@/App.vue';
import router from '@/router';
import '@/styles/global.css';
import 'element-plus/dist/index.css';
import ElementPlus from 'element-plus';

/**
 * 前端应用启动入口。
 *
 * 这和后端的 `DataAgentApplication` 作用类似，负责把一堆静态组件、路由和插件真正装配成可运行的单页应用。
 *
 * 关键框架 API：
 * - `createApp(App)`：
 *   创建 Vue 3 应用实例，`App.vue` 会成为整棵组件树的根节点。
 * - `app.use(...)`：
 *   注册插件。这里注册了：
 *   1. `router`，负责前端页面路由切换。
 *   2. `ElementPlus`，负责全局注入 UI 组件能力。
 * - `app.mount('#app')`：
 *   把 Vue 应用挂载到 `index.html` 里的 DOM 节点上，页面从这一刻开始真正可交互。
 */
const app = createApp(App);

app.use(router);
app.use(ElementPlus);
app.mount('#app');
