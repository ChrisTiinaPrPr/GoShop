package org.example.goshop.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.goshop.agent.config.AgentProperties;
import org.example.goshop.common.exception.BusinessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Agent 用户消息的 Redis 滑动窗口限流器。
 *
 * <p>限流维度固定为 JWT 中的买家用户 ID，客户端无法传入或替换。每个用户
 * 维护一个只保留最近 60 秒请求的 ZSET，从而避免固定整分钟窗口边界处的
 * 瞬时双倍流量。</p>
 *
 * <p>ZSET member 使用 {@code conversationId:clientMessageId}。浏览器因 SSE
 * 断流使用相同幂等键重试时，只会命中已有 member，不会重复占用额度；使用
 * 新幂等键发送的新消息才会消耗一次额度。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentMessageRateLimitService {

    private static final String RATE_LIMIT_KEY_PREFIX =
            "agent:rate-limit:message:v1:";

    /**
     * Redis 端使用自己的 TIME，避免多实例应用服务器时钟不一致。
     *
     * <p>清理过期成员、判断幂等重试、检查额度、写入新成员和刷新 TTL 必须
     * 在同一个 Lua 脚本内原子完成，否则并发请求可能同时通过计数检查。</p>
     */
    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT =
            new DefaultRedisScript<>("""
                    local redisTime = redis.call('TIME')
                    local nowMillis = redisTime[1] * 1000
                            + math.floor(redisTime[2] / 1000)
                    local windowMillis = 60000
                    local keyTtlMillis = 61000
                    local windowStart = nowMillis - windowMillis

                    redis.call(
                            'ZREMRANGEBYSCORE',
                            KEYS[1],
                            '-inf',
                            windowStart
                    )

                    if redis.call('ZSCORE', KEYS[1], ARGV[1]) then
                        redis.call('PEXPIRE', KEYS[1], keyTtlMillis)
                        return 1
                    end

                    local currentCount = redis.call('ZCARD', KEYS[1])
                    if currentCount >= tonumber(ARGV[2]) then
                        redis.call('PEXPIRE', KEYS[1], keyTtlMillis)
                        return 0
                    end

                    redis.call('ZADD', KEYS[1], nowMillis, ARGV[1])
                    redis.call('PEXPIRE', KEYS[1], keyTtlMillis)
                    return 1
                    """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final AgentProperties agentProperties;

    /**
     * 消耗一次新消息额度，或放行相同幂等消息的重试。
     *
     * <p>Redis 是限流的协调组件，但不应成为购物助手的额外单点。Redis
     * 暂时不可用时采用 fail-open，数据库中的单会话并发锁和消息幂等约束
     * 仍然有效；同时记录不包含用户身份和消息正文的告警。</p>
     */
    public void checkAllowed(
            Long userId,
            Long conversationId,
            String clientMessageId
    ) {
        String key = RATE_LIMIT_KEY_PREFIX + userId;
        String requestMember = conversationId + ":" + clientMessageId;

        final Long allowed;
        try {
            allowed = redisTemplate.execute(
                    RATE_LIMIT_SCRIPT,
                    List.of(key),
                    requestMember,
                    String.valueOf(
                            agentProperties.rateLimitPerMinute()
                    )
            );
        } catch (RuntimeException redisFailure) {
            log.warn("Agent 消息限流 Redis 不可用，本次请求降级放行");
            return;
        }

        if (Long.valueOf(0L).equals(allowed)) {
            throw new BusinessException(
                    42901,
                    "购物助手请求过于频繁，请稍后再试"
            );
        }

        if (allowed == null) {
            /*
             * execute 正常应只返回 0 或 1。返回 null 说明 Redis 驱动没有得到
             * 脚本结果，按基础设施故障处理，避免误伤正常买家请求。
             */
            log.warn("Agent 消息限流未返回结果，本次请求降级放行");
        }
    }
}
