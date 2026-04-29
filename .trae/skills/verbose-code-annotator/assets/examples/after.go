// =====================================================================
// 文件说明: 基于 Redis 实现的简易分布式锁组件
// 核心职责: 
//   1. 提供跨进程的互斥访问控制，确保并发场景下的资源操作安全。
// 上下游依赖:
//   - 依赖: go-redis 客户端 (与远程 Redis Server 交互)
// 注意事项: 目前实现为简易版，未实现锁的自动续期（如 Redisson 的 WatchDog 机制）。
//          如果业务长耗时任务超过了 timeout 时间，存在锁提前释放被他人抢占的风险。
// =====================================================================

package lock

import (
    "context"
    "time"

    "github.com/redis/go-redis/v9"
)

// RedisLock 分布式锁结构体
// 内部持有 go-redis 客户端的连接池实例
type RedisLock struct {
    client *redis.Client
}

// Acquire 尝试获取分布式锁
// 功能说明: 使用 Redis 的 SETNX 命令尝试非阻塞加锁，并设置过期时间防止死锁。
// 参数:
//   - ctx (context.Context): 上下文，用于传递超时控制和链路追踪(TraceID)信息。
//   - key (string): 锁的全局唯一标识（通常拼接业务前缀如 "lock:order:1001"）。
//   - timeout (time.Duration): 锁的自动过期时间（兜底释放时间）。
// 返回值:
//   - bool: 抢锁成功返回 true，锁已被占用或发生网络错误返回 false。
// 副作用:
//   - 会向 Redis 写入一条 String 类型的键值对数据。
func (r *RedisLock) Acquire(ctx context.Context, key string, timeout time.Duration) bool {
    // 【知识点】分布式锁的核心机制：SETNX + EXPIRE
    // - SETNX: SET if Not eXists，只有在 key 不存在时才设置成功，这是一个原子操作，利用它来判断是否抢到锁。
    // - 为什么要将 SETNX 和 EXPIRE 放在一起设置？
    //   如果是先发送 SETNX，再发送 EXPIRE 命令，在这两条命令之间如果进程崩溃，会导致该锁永远不会过期，形成死锁。
    //   go-redis 封装的带 timeout 的 SetNX 方法，底层安全地调用了 Redis 2.6.12 之后支持的复合命令：
    //   `SET key value NX EX seconds`，保证了加锁和设置超时的整体原子性。
    // 📚 参考文档：https://redis.io/commands/set/
    res, err := r.client.SetNX(ctx, key, "1", timeout).Result()

    // 如果发生网络超时、连接断开等 Redis 交互异常，
    // 为了确保业务安全，保守处理，认为加锁失败。
    if err != nil {
       return false
    }

    return res
}