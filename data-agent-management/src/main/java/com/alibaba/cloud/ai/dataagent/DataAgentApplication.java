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
package com.alibaba.cloud.ai.dataagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * DataAgent 后端服务的启动入口。
 *
 * 这个类虽然只有一行真正执行代码，但它是整个 Spring Boot 应用的“开机按钮”。
 * 新同学阅读后端时建议先看这里，原因不是逻辑复杂，而是它定义了应用从哪里开始完成以下事情：
 * 1. 扫描当前包及子包中的 Controller、Service、Configuration 等组件。
 * 2. 根据依赖和配置文件完成自动装配。
 * 3. 启动 WebFlux 运行时与定时任务基础设施。
 *
 * 关键框架注解：
 * - {@link SpringBootApplication}：
 *   等价于 `@Configuration + @EnableAutoConfiguration + @ComponentScan`，
 *   表示当前类所在包是 Spring 组件扫描的根。
 * - {@link EnableScheduling}：
 *   开启 `@Scheduled` 定时任务支持，项目中的资源清理、初始化、补偿任务都依赖它。
 */
@EnableScheduling
@SpringBootApplication
public class DataAgentApplication {

	/**
	 * 启动 Spring Boot 应用。
	 *
	 * `SpringApplication.run(...)` 会创建 `ApplicationContext`，装配全部 Bean，
	 * 启动内嵌服务器，并触发各类启动回调。可以把它理解成：
	 * “把静态源码装配成一个真正对外提供服务的后端进程”。
	 */
	public static void main(String[] args) {
		SpringApplication.run(DataAgentApplication.class, args);
	}

}
