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
package com.alibaba.cloud.ai.dataagent.service.code;

/**
 * 运行Python任务的容器池接口
 *
 * @author vlsmb
 * @since 2025/7/12
 */
public interface CodePoolExecutorService {

	TaskResponse runTask(TaskRequest request);

	record TaskRequest(String code, String input, String requirement) {

	}

	record TaskResponse(boolean isSuccess, boolean executionSuccessButResultFailed, String stdOut, String stdErr,
			String exceptionMsg) {

		// 执行运行代码任务时发生异常
		public static TaskResponse exception(String msg) {
			return new TaskResponse(false, false, null, null, "An exception occurred while executing the task: " + msg);
		}

		// 执行运行代码任务成功，并且代码正常返回
		public static TaskResponse success(String stdOut) {
			return new TaskResponse(true, false, stdOut, null, null);
		}

		// 执行运行代码任务成功，但是代码异常返回
		/**
		 * 功能概述：构造“进程执行成功但业务执行失败”的结果对象。
		 * 输入输出：`stdOut` 为标准输出，`stdErr` 为错误输出；返回失败态 `TaskResponse`。
		 * 调用关系：由代码执行器在捕获到脚本异常输出时调用。
		 * 注意事项：该分支表示子进程成功返回但任务语义失败，需与系统异常区分处理。
		 */
		public static TaskResponse failure(String stdOut, String stdErr) {
			return new TaskResponse(false, true, stdOut, stdErr, "StdErr: " + stdErr);
		}

		@Override
		/**
		 * 功能概述：输出任务响应对象的可读字符串，便于日志记录和问题排查。
		 * 输入输出：无入参；返回包含关键字段的字符串表示。
		 * 调用关系：通常由日志框架或调试打印触发调用。
		 * 注意事项：为避免日志泄露，敏感字段应在上游写入前进行脱敏控制。
		 */
		public String toString() {
			return "TaskResponse{" + "isSuccess=" + isSuccess + ", stdOut='" + stdOut + '\'' + ", stdErr='" + stdErr
					+ '\'' + ", exceptionMsg='" + exceptionMsg + '\'' + '}';
		}
	}

	enum State {

		READY, RUNNING, REMOVING

	}

}
