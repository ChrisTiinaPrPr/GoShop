package org.example.goshop.product.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.goshop.product.mapper.ProductDetailResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商品详情缓存防护策略单元测试。
 *
 * <p>测试不连接真实 Redis，通过 Mockito 验证命中、空值、互斥重建、等待和故障降级。</p>
 */
class ProductDetailCacheServiceTest {

    private static final Long PRODUCT_ID = 2001L;
    private static final String DETAIL_KEY = "product:detail:v1:" + PRODUCT_ID;
    private static final String LOCK_KEY = "product:detail:lock:v1:" + PRODUCT_ID;

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private ObjectMapper objectMapper;
    private ProductCacheProperties properties;
    private ProductDetailCacheService cacheService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        objectMapper = new ObjectMapper();

        properties = new ProductCacheProperties();
        properties.setDetailTtl(Duration.ofMinutes(30));
        properties.setNullTtl(Duration.ofMinutes(2));
        properties.setMaxTtlJitter(Duration.ZERO);
        properties.setLockTtl(Duration.ofSeconds(10));
        properties.setLockWait(Duration.ofMillis(1));
        properties.setLockRetryTimes(2);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        cacheService = new ProductDetailCacheService(
                redisTemplate,
                objectMapper,
                properties
        );
    }

    @Test
    void shouldReturnCachedDetailWithoutDatabaseLoad() throws Exception {
        ProductDetailResponse cachedDetail = detail();
        when(valueOperations.get(DETAIL_KEY))
                .thenReturn(objectMapper.writeValueAsString(cachedDetail));
        AtomicInteger databaseLoads = new AtomicInteger();

        ProductDetailResponse result = cacheService.getOrLoad(
                PRODUCT_ID,
                () -> {
                    databaseLoads.incrementAndGet();
                    return detail();
                }
        );

        assertEquals(PRODUCT_ID, result.id());
        assertEquals(0, databaseLoads.get());
        verify(valueOperations, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void shouldUseShortCachedNullToStopPenetration() {
        when(valueOperations.get(DETAIL_KEY))
                .thenReturn("__GOSHOP_PRODUCT_NOT_FOUND__");
        AtomicInteger databaseLoads = new AtomicInteger();

        ProductDetailResponse result = cacheService.getOrLoad(
                PRODUCT_ID,
                () -> {
                    databaseLoads.incrementAndGet();
                    return detail();
                }
        );

        assertNull(result);
        assertEquals(0, databaseLoads.get());
    }

    @Test
    void shouldRebuildOnceUnderMutexAndApplyConfiguredTtl() {
        ProductDetailResponse databaseDetail = detail();
        when(valueOperations.get(DETAIL_KEY)).thenReturn(null);
        when(valueOperations.setIfAbsent(
                eq(LOCK_KEY),
                anyString(),
                eq(Duration.ofSeconds(10))
        )).thenReturn(true);

        ProductDetailResponse result = cacheService.getOrLoad(
                PRODUCT_ID,
                () -> databaseDetail
        );

        assertSame(databaseDetail, result);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(
                eq(DETAIL_KEY),
                anyString(),
                ttlCaptor.capture()
        );
        assertEquals(Duration.ofMinutes(30), ttlCaptor.getValue());
        verify(redisTemplate).execute(any(), eq(List.of(LOCK_KEY)), anyString());
    }

    @Test
    void shouldCacheMissingProductWithShortTtl() {
        when(valueOperations.get(DETAIL_KEY)).thenReturn(null);
        when(valueOperations.setIfAbsent(
                eq(LOCK_KEY),
                anyString(),
                eq(Duration.ofSeconds(10))
        )).thenReturn(true);

        ProductDetailResponse result = cacheService.getOrLoad(PRODUCT_ID, () -> null);

        assertNull(result);
        verify(valueOperations).set(
                DETAIL_KEY,
                "__GOSHOP_PRODUCT_NOT_FOUND__",
                Duration.ofMinutes(2)
        );
    }

    @Test
    void shouldWaitForLockHolderInsteadOfRebuildingSameHotKey() throws Exception {
        String rebuiltJson = objectMapper.writeValueAsString(detail());
        when(valueOperations.get(DETAIL_KEY))
                .thenReturn(null, rebuiltJson);
        when(valueOperations.setIfAbsent(
                eq(LOCK_KEY),
                anyString(),
                eq(Duration.ofSeconds(10))
        )).thenReturn(false);
        AtomicInteger databaseLoads = new AtomicInteger();

        ProductDetailResponse result = cacheService.getOrLoad(
                PRODUCT_ID,
                () -> {
                    databaseLoads.incrementAndGet();
                    return detail();
                }
        );

        assertEquals(PRODUCT_ID, result.id());
        assertEquals(0, databaseLoads.get());
    }

    @Test
    void shouldFallBackToDatabaseWhenRedisUnavailable() {
        when(valueOperations.get(DETAIL_KEY))
                .thenThrow(new RedisConnectionFailureException("redis down"));
        ProductDetailResponse databaseDetail = detail();

        ProductDetailResponse result = cacheService.getOrLoad(
                PRODUCT_ID,
                () -> databaseDetail
        );

        assertSame(databaseDetail, result);
    }

    @Test
    void shouldAddIndependentTtlJitterToReduceAvalancheRisk() {
        properties.setMaxTtlJitter(Duration.ofMinutes(10));
        when(valueOperations.get(DETAIL_KEY)).thenReturn(null);
        when(valueOperations.setIfAbsent(
                eq(LOCK_KEY),
                anyString(),
                eq(Duration.ofSeconds(10))
        )).thenReturn(true);

        cacheService.getOrLoad(PRODUCT_ID, this::detail);

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(
                eq(DETAIL_KEY),
                anyString(),
                ttlCaptor.capture()
        );
        Duration actualTtl = ttlCaptor.getValue();
        assertTrue(actualTtl.compareTo(Duration.ofMinutes(30)) >= 0);
        assertTrue(actualTtl.compareTo(Duration.ofMinutes(40)) <= 0);
    }

    @Test
    void shouldEvictChangedProductImmediatelyWithoutTransaction() {
        cacheService.evictAfterCommit(PRODUCT_ID);

        verify(redisTemplate).delete(List.of(DETAIL_KEY));
    }

    @Test
    void shouldDelayEvictionUntilDatabaseTransactionCommitted() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            cacheService.evictAfterCommit(PRODUCT_ID);

            // 数据库尚未提交时不能先删缓存，否则并发请求可能把旧数据重新写回 Redis。
            verify(redisTemplate, never()).delete(List.of(DETAIL_KEY));

            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, synchronizations.size());
            synchronizations.get(0).afterCommit();

            verify(redisTemplate).delete(List.of(DETAIL_KEY));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private ProductDetailResponse detail() {
        return new ProductDetailResponse(
                PRODUCT_ID,
                3001L,
                4001L,
                "热点商品",
                "商品描述",
                "https://example.test/product.jpg",
                99L,
                List.of(),
                List.of()
        );
    }
}
