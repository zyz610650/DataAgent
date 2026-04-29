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
package com.alibaba.cloud.ai.dataagent.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
/**
 * PageResult：接口返回视图对象。
 *
 * 它把内部PageResult结果整理成更适合前端或调用方读取的输出结构。
 * 学习这类类时，重点关注哪些字段是展示用、哪些字段承担流程控制作用。
 */
public class PageResult<T> {

	/**
	 * 数据列表
	 */
	private List<T> data;

	/**
	 * 总记录数
	 */
	private Long total;

	/**
	 * 当前页码
	 */
	private Integer pageNum;

	/**
	 * 每页大小
	 */
	private Integer pageSize;

	/**
	 * 总页数
	 */
	private Integer totalPages;

	/**
 * `calculateTotalPages`：执行当前类对外暴露的一步核心操作。
 *
 * 阅读这个方法时，建议同时关注它依赖了什么输入，以及结果最后会被哪一层继续消费。
 */
	public void calculateTotalPages() {
		if (this.total != null && this.pageSize != null && this.pageSize > 0) {
			this.totalPages = (int) Math.ceil((double) this.total / this.pageSize);
		}
		else {
			this.totalPages = 0;
		}
	}

}
