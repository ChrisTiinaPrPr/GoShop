package org.example.goshop.product.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.goshop.product.mapper.ProductDetailResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * 公开商品详情的 Redis Cache Aside 实现。
 *
 * <p>防护策略：</p>
 * <ul>
 *     <li>缓存穿透：不存在或不可见商品写入短 TTL 空值标记；</li>
 *     <li>缓存击穿：缓存失效时使用带租约的 Redis 互斥锁，锁内二次检查；</li>
 *     <li>缓存雪崩：每个详情和空值缓存都附加独立随机 TTL 抖动；</li>
 *     <li>缓存故障：Redis 读写异常时降级查询数据库，不让缓存成为商品接口单点；</li>
 *     <li>缓存一致性：写业务只在数据库事务提交成功后删除详情缓存。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductDetailCacheService {

    private static final String DETAIL_KEY_PREFIX = "product:detail:v1:";
    private static final String LOCK_KEY_PREFIX = "product:detail:lock:v1:";
    private static final String NULL_MARKER = "__GOSHOP_PRODUCT_NOT_FOUND__";

    /**
     * 只能删除自己持有的锁，避免持锁线程超时后误删其他请求的新锁。
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT =
            new DefaultRedisScript<>("""
                    if redis.call('GET', KEYS[1]) == ARGV[1] then
                        return redis.call('DEL', KEYS[1])
                    end
                    return 0
                    """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ProductCacheProperties properties;

    /**
     * 优先读取缓存；未命中时由调用方提供的 loader 查询数据库并重建缓存。
     * loader 返回 null 表示商品不存在、已下架或没有启用 SKU。
     */
    public ProductDetailResponse getOrLoad(
            Long productId,
            Supplier<ProductDetailResponse> loader
    ) {
        if (!properties.isEnabled()) {
            return loader.get();
        }

        String detailKey = detailKey(productId);
        CacheLookup initialLookup;
        try {
            initialLookup = readCache(detailKey, productId);
        } catch (RuntimeException redisFailure) {
            log.warn("读取商品详情缓存失败，降级数据库，productId={}", productId);
            return loader.get();
        }

        if (initialLookup.resolved()) {
            return initialLookup.value();
        }

        String lockKey = lockKey(productId);
        String lockToken = UUID.randomUUID().toString();
        final boolean locked;
        try {
            locked = Boolean.TRUE.equals(
                    redisTemplate.opsForValue().setIfAbsent(
                            lockKey,
                            lockToken,
                            properties.getLockTtl()
                    )
            );
        } catch (RuntimeException redisFailure) {
            log.warn("获取商品缓存重建锁失败，降级数据库，productId={}", productId);
            return loader.get();
        }

        if (locked) {
            return rebuildWhileLocked(
                    productId,
                    detailKey,
                    lockKey,
                    lockToken,
                    loader
            );
        }

        return waitForRebuildOrFallback(productId, detailKey, loader);
    }

    /**
     * 数据库写事务提交成功后失效一个商品详情。
     */
    public void evictAfterCommit(Long productId) {
        evictAfterCommit(List.of(productId));
    }

    /**
     * 批量库存变更时去重商品 ID，并在事务提交后统一删除缓存。
     */
    public void evictAfterCommit(Collection<Long> productIds) {
        Set<Long> normalizedIds = new LinkedHashSet<>();
        if (productIds != null) {
            productIds.stream()
                    .filter(id -> id != null && id > 0)
                    .forEach(normalizedIds::add);
        }
        if (normalizedIds.isEmpty() || !properties.isEnabled()) {
            return;
        }

        Runnable eviction = () -> evictNow(normalizedIds);
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            eviction.run();
                        }
                    }
            );
            return;
        }

        eviction.run();
    }

    private ProductDetailResponse rebuildWhileLocked(
            Long productId,
            String detailKey,
            String lockKey,
            String lockToken,
            Supplier<ProductDetailResponse> loader
    ) {
        try {
            /*
             * 获取锁之前可能已有其他请求完成重建，因此锁内必须二次检查，
             * 否则排队请求仍会重复查询数据库。
             */
            CacheLookup secondLookup = readCache(detailKey, productId);
            if (secondLookup.resolved()) {
                return secondLookup.value();
            }

            ProductDetailResponse loaded = loader.get();
            writeCache(detailKey, productId, loaded);
            return loaded;
        } catch (RedisCacheAccessException redisFailure) {
            // 锁已获得但 Redis 随后故障，业务仍以数据库结果为准。
            log.warn("重建商品详情缓存失败，降级数据库，productId={}", productId);
            return loader.get();
        } finally {
            unlockSafely(lockKey, lockToken, productId);
        }
    }

    private ProductDetailResponse waitForRebuildOrFallback(
            Long productId,
            String detailKey,
            Supplier<ProductDetailResponse> loader
    ) {
        for (int attempt = 0; attempt < properties.getLockRetryTimes(); attempt++) {
            if (!sleepBeforeRetry()) {
                break;
            }

            try {
                CacheLookup lookup = readCache(detailKey, productId);
                if (lookup.resolved()) {
                    return lookup.value();
                }
            } catch (RuntimeException redisFailure) {
                log.warn("等待商品缓存重建时 Redis 不可用，降级数据库，productId={}", productId);
                return loader.get();
            }
        }

        /*
         * 锁持有者超过等待窗口时优先保证接口可用性，直接查询数据库但不回写缓存。
         * 这样不会覆盖仍在构建中的新值，也把等待时间限制在可配置上限内。
         */
        log.warn("等待商品缓存重建超时，降级数据库，productId={}", productId);
        return loader.get();
    }

    private CacheLookup readCache(String detailKey, Long productId) {
        final String cached;
        try {
            cached = redisTemplate.opsForValue().get(detailKey);
        } catch (RuntimeException e) {
            throw new RedisCacheAccessException(e);
        }

        if (cached == null) {
            return CacheLookup.miss();
        }
        if (NULL_MARKER.equals(cached)) {
            return CacheLookup.cachedNull();
        }

        try {
            return CacheLookup.hit(
                    objectMapper.readValue(cached, ProductDetailResponse.class)
            );
        } catch (JsonProcessingException corruptedCache) {
            // 异常缓存不应永久污染接口；删除后按普通未命中重建。
            log.warn("商品详情缓存 JSON 损坏，删除后重建，productId={}", productId);
            deleteSafely(detailKey);
            return CacheLookup.miss();
        }
    }

    private void writeCache(
            String detailKey,
            Long productId,
            ProductDetailResponse detail
    ) {
        try {
            if (detail == null) {
                redisTemplate.opsForValue().set(
                        detailKey,
                        NULL_MARKER,
                        withJitter(properties.getNullTtl())
                );
                return;
            }

            redisTemplate.opsForValue().set(
                    detailKey,
                    objectMapper.writeValueAsString(detail),
                    withJitter(properties.getDetailTtl())
            );
        } catch (JsonProcessingException | RuntimeException cacheFailure) {
            log.warn("写入商品详情缓存失败，productId={}", productId);
            // 写缓存失败不能回滚已经成功的数据库读取。
        }
    }

    private Duration withJitter(Duration baseTtl) {
        long jitterMillis = Math.max(0L, properties.getMaxTtlJitter().toMillis());
        if (jitterMillis == 0L) {
            return baseTtl;
        }
        long randomMillis = ThreadLocalRandom.current().nextLong(jitterMillis + 1L);
        return baseTtl.plusMillis(randomMillis);
    }

    private boolean sleepBeforeRetry() {
        try {
            Thread.sleep(Math.max(1L, properties.getLockWait().toMillis()));
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void unlockSafely(String lockKey, String lockToken, Long productId) {
        try {
            redisTemplate.execute(
                    UNLOCK_SCRIPT,
                    List.of(lockKey),
                    lockToken
            );
        } catch (RuntimeException redisFailure) {
            // 锁具有短 TTL，即使主动释放失败也会自动过期。
            log.warn("释放商品缓存重建锁失败，等待租约自动过期，productId={}", productId);
        }
    }

    private void evictNow(Set<Long> productIds) {
        List<String> keys = productIds.stream().map(this::detailKey).toList();
        try {
            redisTemplate.delete(keys);
        } catch (RuntimeException redisFailure) {
            /*
             * 缓存失效失败不能回滚已经提交的商品或库存事务。
             * 现有缓存仍受 TTL 限制，后续可通过日志告警定位 Redis 故障。
             */
            log.warn("商品详情缓存批量失效失败，productCount={}", productIds.size());
        }
    }

    private void deleteSafely(String key) {
        try {
            redisTemplate.delete(key);
        } catch (RuntimeException ignored) {
            // 下次读取仍会再次尝试重建，删除失败不影响数据库降级路径。
        }
    }

    private String detailKey(Long productId) {
        return DETAIL_KEY_PREFIX + productId;
    }

    private String lockKey(Long productId) {
        return LOCK_KEY_PREFIX + productId;
    }

    private enum CacheState {
        MISS,
        HIT,
        CACHED_NULL
    }

    private record CacheLookup(CacheState state, ProductDetailResponse value) {

        static CacheLookup miss() {
            return new CacheLookup(CacheState.MISS, null);
        }

        static CacheLookup hit(ProductDetailResponse value) {
            return new CacheLookup(CacheState.HIT, value);
        }

        static CacheLookup cachedNull() {
            return new CacheLookup(CacheState.CACHED_NULL, null);
        }

        boolean resolved() {
            return state != CacheState.MISS;
        }
    }

    private static final class RedisCacheAccessException extends RuntimeException {

        private RedisCacheAccessException(Throwable cause) {
            super(cause);
        }
    }
}
