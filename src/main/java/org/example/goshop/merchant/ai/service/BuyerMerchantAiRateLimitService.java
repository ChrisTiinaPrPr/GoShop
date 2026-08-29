package org.example.goshop.merchant.ai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.goshop.common.exception.BusinessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** 买家店铺导购提问的 Redis 滑动窗口限流器。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuyerMerchantAiRateLimitService {

    private static final String KEY_PREFIX =
            "merchant-ai:buyer-question:rate:v1:";
    private static final int REQUESTS_PER_MINUTE = 10;

    /**
     * Redis TIME、过期清理、计数判断和写入在同一 Lua 脚本中完成，避免
     * 多应用实例并发时都通过额度检查。ZSET 仅保留最近 60 秒成员。
     */
    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT =
            new DefaultRedisScript<>("""
                    local redisTime = redis.call('TIME')
                    local nowMillis = redisTime[1] * 1000
                            + math.floor(redisTime[2] / 1000)
                    local windowStart = nowMillis - 60000
                    redis.call(
                            'ZREMRANGEBYSCORE',
                            KEYS[1],
                            '-inf',
                            windowStart
                    )
                    if redis.call('ZCARD', KEYS[1])
                            >= tonumber(ARGV[2]) then
                        redis.call('PEXPIRE', KEYS[1], 61000)
                        return 0
                    end
                    redis.call('ZADD', KEYS[1], nowMillis, ARGV[1])
                    redis.call('PEXPIRE', KEYS[1], 61000)
                    return 1
                    """, Long.class);

    private final StringRedisTemplate redisTemplate;

    /**
     * 限流身份完全来自 JWT 和店铺路径，客户端不能伪造 Redis key。
     * Redis 暂时不可用时 fail-open，避免缓存基础设施拖垮正常商城入口。
     */
    public void checkAllowed(Long buyerUserId, Long merchantId) {
        String key = KEY_PREFIX + buyerUserId + ":" + merchantId;
        try {
            Long allowed = redisTemplate.execute(
                    RATE_LIMIT_SCRIPT,
                    List.of(key),
                    UUID.randomUUID().toString(),
                    String.valueOf(REQUESTS_PER_MINUTE)
            );
            if (Long.valueOf(0L).equals(allowed)) {
                throw new BusinessException(
                        42901,
                        "智能导购请求过于频繁，请稍后再试"
                );
            }
            if (allowed == null) {
                log.warn("智能导购限流未返回结果，本次请求降级放行");
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException redisFailure) {
            log.warn("智能导购限流 Redis 不可用，本次请求降级放行");
        }
    }
}
