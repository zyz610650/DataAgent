<!--
  Copyright 2024-2025 the original author or authors.

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

       https://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->
<template>
  <div id="app">
    <!--
      根组件本身不承载业务页面，只提供一个统一挂载点。
      真正的页面切换由 Vue Router 控制，当前匹配到的页面组件会渲染在 <router-view /> 中。
    -->
    <router-view />
  </div>
</template>

<script>
  export default {
    /**
     * 根组件。
     *
     * 这个文件刻意保持很薄，因为它只负责承接整个应用的页面路由。
     * 当项目规模变大后，如果需要全局主题、全局弹层、权限上下文等能力，也通常会从这里往上挂。
     */
    name: 'App',
  };
</script>

<style>
  #app {
    min-height: 100vh;
  }
</style>
