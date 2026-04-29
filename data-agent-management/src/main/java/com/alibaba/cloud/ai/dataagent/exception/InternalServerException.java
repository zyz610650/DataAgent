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
package com.alibaba.cloud.ai.dataagent.exception;

/**
 * InternalServerException：自定义业务异常。
 *
 * 它用来表达InternalServer相关的可预期失败场景，方便统一做错误码映射和接口返回。
 * 重点看它由谁抛出、由谁捕获，以及最终如何反馈给调用方。
 */
public class InternalServerException extends RuntimeException {

	public InternalServerException(String message) {
		super(message);
	}

}
