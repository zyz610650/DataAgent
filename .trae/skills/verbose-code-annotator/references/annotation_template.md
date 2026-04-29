# 多语言后端注释模板 (Annotation Templates)

本模板用于 `data-agent-management` 后端代码注释。目标是让注释具备“学习教程”价值，而不是仅复述代码。

## 通用模板（函数/方法）

```text
[功能概述] 该函数在业务流程中的目标和角色。
[调用关系] 上游谁调用它，下游它调用谁。
[输入输出] 关键参数含义、返回值语义、异常条件。
[业务背景] 对应业务实体或流程阶段。
[设计考虑] 为什么这样实现（性能/一致性/安全权衡）。
[注意事项] 仅在确有风险时补充（并发、边界、重试、幂等）。
[参考文档] 内部 docs 路径或官方 URL。
```

## Java 模板（Javadoc + 行内）

```java
/**
 * [功能概述] 处理订单审批主流程。
 * [系统角色] Service 层核心编排点，连接 Controller 与 Repository。
 * @param req 审批请求，包含订单ID、审批动作与操作人信息。
 * @return 审批结果，包含新状态与审计流水号。
 * @throws BizException 当状态流转不合法或下游服务不可用时抛出。
 * [调用关系] 上游由 OrderController 调用，下游调用库存服务与审计服务。
 * [设计考虑] 先写审计再改状态，保证问题排查可追溯。
 */
public ApprovalResult approve(ApproveRequest req) {
    // [关键步骤] 幂等校验：避免重复请求导致状态重复流转。
    // [外部依赖] 调用库存 API 前必须设置 traceId 便于链路追踪。
}
```

## Go 模板（类型/函数前置注释 + 关键行内）

```go
// OrderService [功能概述] 订单业务服务，负责订单状态流转与跨服务编排。
// [业务背景] 该服务承接下单、支付、取消等关键链路。
type OrderService struct {
    repo OrderRepo
}

// CancelOrder [功能概述] 取消订单并触发资源回滚。
// [输入输出] orderID 为订单主键；返回 error 表示失败原因。
// [调用关系] 上游由 HTTP Handler 调用，下游调用库存回滚与消息投递。
// [设计考虑] 先本地事务落库，再发 MQ，降低跨系统不一致概率。
func (s *OrderService) CancelOrder(ctx context.Context, orderID string) error {
    // [关键步骤] 校验状态是否允许取消，避免非法状态流转。
    return nil
}
```

## Python 模板（Docstring + 关键 #）

```python
def refresh_token(user_id: str, ttl_sec: int) -> str:
    """
    [功能概述] 刷新用户访问令牌并返回新 token。
    [输入输出] user_id 为用户标识；ttl_sec 为有效期秒数；返回新 token 字符串。
    [调用关系] 上游由鉴权中间件调用，下游写入 Redis。
    [设计考虑] 采用短有效期 + 刷新机制，降低泄漏风险。
    [注意事项] 当 Redis 不可用时应降级并返回可观测错误。
    """
    # [关键步骤] 生成 token 前先检查用户状态是否正常。
    return "new-token"
```

## 中间件/API 注释模板（强制）

```text
[外部集成] 调用 <中间件/API 名称>，用于 <在系统中的作用>。
[关键参数] <参数1>: 含义；<参数2>: 含义。
[返回结构] 成功/失败返回字段语义。
[异常处理] 常见错误码、重试策略、超时与限流策略。
[幂等性] 是否要求请求幂等，以及当前实现如何保障。
[参考文档] 内部文档: docs/... 或 官方文档: https://...
```

## 不应添加的注释

- 仅复述语句字面的注释，例如“i 加 1”“返回 true”。
- 对简单 getter/setter 的过度注释。
- 无法从代码和文档确认、仅凭猜测的背景说明。
