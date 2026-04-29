package lock

import (
	"context"
	"time"

	"github.com/redis/go-redis/v9"
)

type RedisLock struct {
	client *redis.Client
}

func (r *RedisLock) Acquire(ctx context.Context, key string, timeout time.Duration) bool {
	res, err := r.client.SetNX(ctx, key, "1", timeout).Result()
	if err != nil {
		return false
	}
	return res
}