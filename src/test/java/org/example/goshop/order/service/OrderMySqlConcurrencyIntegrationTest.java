package org.example.goshop.order.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.infrastructure.mq.MqProperties;
import org.example.goshop.infrastructure.mq.outbox.MqOutboxService;
import org.example.goshop.order.dto.SubmitOrderItemRequest;
import org.example.goshop.order.dto.SubmitOrderRequest;
import org.example.goshop.order.dto.SubmitOrderResponse;
import org.example.goshop.order.mapper.MallOrderMapper;
import org.example.goshop.order.mapper.OrderItemMapper;
import org.example.goshop.order.mapper.OrderSubmitRecordMapper;
import org.example.goshop.product.cache.ProductDetailCacheService;
import org.example.goshop.product.entity.ProductSpu;
import org.example.goshop.product.mapper.ProductSkuMapper;
import org.example.goshop.product.mapper.ProductSpuMapper;
import org.example.goshop.user.mapper.UserAddressMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 订单库存与幂等的真实 MySQL 8 集成测试。
 *
 * <p>测试使用独立 Testcontainers 数据库，验证 InnoDB 唯一键等待、条件更新、
 * 行锁和事务回滚语义。它不会连接或修改开发者本地的 goshop 数据库。</p>
 */
/**
 * Docker 不可用时跳过真实 MySQL 用例；其余单元测试仍应正常执行。
 * 开发机或 CI 提供 Docker 后，Testcontainers 会自动恢复完整集成验证。
 */
@Testcontainers(disabledWithoutDocker = true)
class OrderMySqlConcurrencyIntegrationTest {

    private static final Long USER_ID = 1001L;
    private static final Long ADDRESS_ID = 2001L;
    private static final Long SKU_ID = 3001L;
    private static final Long SPU_ID = 4001L;
    private static final Long MERCHANT_ID = 5001L;
    private static final String REQUEST_HASH_A = "a".repeat(64);
    private static final String REQUEST_HASH_B = "b".repeat(64);

    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("goshop_order_test")
            .withUsername("goshop")
            .withPassword("goshop-test-password");

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbcTemplate;
    private static OrderTransactionService transactionService;
    private static ProductSkuMapper productSkuMapper;
    private static ProductSpuMapper productSpuMapper;
    private static TransactionTemplate transactionTemplate;

    @BeforeAll
    static void startIsolatedMySql() throws SQLException {
        MYSQL.start();
        context = new AnnotationConfigApplicationContext(IntegrationConfiguration.class);
        jdbcTemplate = context.getBean(JdbcTemplate.class);
        transactionService = context.getBean(OrderTransactionService.class);
        productSkuMapper = context.getBean(ProductSkuMapper.class);
        productSpuMapper = context.getBean(ProductSpuMapper.class);
        transactionTemplate = new TransactionTemplate(
                context.getBean(PlatformTransactionManager.class)
        );

        try (Connection connection = context.getBean(DataSource.class).getConnection()) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new ClassPathResource("order-test-schema.sql")
            );
            // 直接执行生产迁移脚本，确保幂等表 DDL 在真实 MySQL 8 上可落地。
            ScriptUtils.executeSqlScript(
                    connection,
                    new FileSystemResource("order-idempotency-migration.sql")
            );
        }
    }

    @AfterAll
    static void stopIsolatedMySql() {
        if (context != null) {
            context.close();
        }
        MYSQL.stop();
    }

    @BeforeEach
    void resetData() {
        jdbcTemplate.update("DELETE FROM order_submit_record");
        jdbcTemplate.update("DELETE FROM order_item");
        jdbcTemplate.update("DELETE FROM mall_order");
        jdbcTemplate.update("DELETE FROM product_sku");
        jdbcTemplate.update("DELETE FROM product_spu");
        jdbcTemplate.update("DELETE FROM user_address");

        jdbcTemplate.update("""
                INSERT INTO user_address (
                    id, user_id, receiver, phone, province, city, district, detail
                ) VALUES (?, ?, '测试用户', '13800000000', '广东省', '深圳市', '南山区', '测试路 1 号')
                """, ADDRESS_ID, USER_ID);
        insertSpu(SPU_ID, MERCHANT_ID, 1);
        insertSku(SKU_ID, SPU_ID, 100L, 10, 1);
    }

    /** 同一幂等键并发进入 MySQL 时，两次调用必须返回同一订单且只扣一次库存。 */
    @Test
    void shouldCreateOnlyOneOrderForConcurrentSameIdempotencyKey() throws Exception {
        SubmitOrderRequest request = request(new SubmitOrderItemRequest(SKU_ID, 1));
        CountDownLatch start = new CountDownLatch(1);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        AtomicBoolean firstRedisClaim = new AtomicBoolean(true);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(ignored -> firstRedisClaim.getAndSet(false));
        when(values.get(anyString())).thenReturn(null);
        OrderService orderService = orderService(redisTemplate);

        List<SubmitOrderResponse> responses = runConcurrently(
                () -> submitAfterSignal(start, orderService, "same-key", request),
                () -> submitAfterSignal(start, orderService, "same-key", request),
                start
        );

        assertEquals(1L, count("mall_order"));
        assertEquals(1L, count("order_submit_record"));
        assertEquals(9, stock(SKU_ID));
        assertEquals(
                responses.get(0).orders().get(0).orderNo(),
                responses.get(1).orders().get(0).orderNo()
        );
    }

    /** 同一幂等键若请求摘要不同，必须返回冲突且保留首次订单与库存结果。 */
    @Test
    void shouldRejectDifferentRequestBodyForSameIdempotencyKey() {
        transactionService.createOrdersIdempotently(
                USER_ID,
                "conflicting-key",
                REQUEST_HASH_A,
                request(new SubmitOrderItemRequest(SKU_ID, 1))
        );

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> transactionService.createOrdersIdempotently(
                        USER_ID,
                        "conflicting-key",
                        REQUEST_HASH_B,
                        request(new SubmitOrderItemRequest(SKU_ID, 2))
                )
        );

        assertEquals(40901, exception.getCode());
        assertEquals(1L, count("mall_order"));
        assertEquals(9, stock(SKU_ID));
    }

    /** 两个事务争抢最后一件库存时，条件更新只能让一个事务成功。 */
    @Test
    void shouldAllowOnlyOneTransactionToDeductLastStockItem() throws Exception {
        jdbcTemplate.update("UPDATE product_sku SET stock = 1 WHERE id = ?", SKU_ID);
        CountDownLatch start = new CountDownLatch(1);

        List<Integer> affectedRows = runConcurrently(
                () -> deductAfterSignal(start),
                () -> deductAfterSignal(start),
                start
        );

        assertEquals(1, affectedRows.stream().mapToInt(Integer::intValue).sum());
        assertEquals(0, stock(SKU_ID));
    }

    /** 多 SKU 下单中后一个库存不足时，前一个 SKU 的扣减和全部订单写入必须回滚。 */
    @Test
    void shouldRollbackAllSkuDeductionsWhenOneSkuIsOutOfStock() {
        Long secondSkuId = 3002L;
        insertSku(secondSkuId, SPU_ID, 200L, 0, 1);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> transactionService.createOrders(
                        USER_ID,
                        request(
                                new SubmitOrderItemRequest(SKU_ID, 1),
                                new SubmitOrderItemRequest(secondSkuId, 1)
                        )
                )
        );

        assertEquals(40902, exception.getCode());
        assertEquals(10, stock(SKU_ID));
        assertEquals(0L, count("mall_order"));
        assertEquals(0L, count("order_item"));
    }

    /** 商品完成普通查询校验后被另一事务下架，扣库存 SQL 必须再次检查 SPU 状态并失败。 */
    @Test
    void shouldRejectDeductionWhenSpuIsDisabledAfterValidation() throws Exception {
        CountDownLatch validated = new CountDownLatch(1);
        CountDownLatch disabled = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> deduction = executor.submit(() -> transactionTemplate.execute(status -> {
                ProductSpu validatedSpu = productSpuMapper.selectById(SPU_ID);
                assertEquals(1, validatedSpu.getStatus());
                validated.countDown();
                await(disabled);
                return productSkuMapper.deductAvailableStock(SKU_ID, 1);
            }));
            Future<Integer> disable = executor.submit(() -> {
                await(validated);
                int rows = jdbcTemplate.update(
                        "UPDATE product_spu SET status = 0 WHERE id = ?",
                        SPU_ID
                );
                disabled.countDown();
                return rows;
            });

            assertEquals(1, disable.get(10, TimeUnit.SECONDS));
            assertEquals(0, deduction.get(10, TimeUnit.SECONDS));
            assertEquals(10, stock(SKU_ID));
        } finally {
            executor.shutdownNow();
        }
    }

    /** MySQL 已提交后 Redis 回写失败，重复请求应从事实表恢复原响应而不是创建第二张订单。 */
    @Test
    void shouldRecoverCommittedResultWhenRedisWriteFails() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true)
                .thenThrow(new RedisConnectionFailureException("redis down"));
        when(values.get(anyString()))
                .thenThrow(new RedisConnectionFailureException("redis down"));
        doThrow(new RedisConnectionFailureException("redis down"))
                .when(values)
                .set(anyString(), anyString(), any(Duration.class));

        OrderService orderService = orderService(redisTemplate);
        SubmitOrderRequest request = request(new SubmitOrderItemRequest(SKU_ID, 1));

        SubmitOrderResponse first = orderService.submitOrder(
                USER_ID,
                "redis-failure-key",
                request
        );
        SubmitOrderResponse recovered = orderService.submitOrder(
                USER_ID,
                "redis-failure-key",
                request
        );

        assertEquals(1L, count("mall_order"));
        assertEquals(9, stock(SKU_ID));
        assertEquals(
                first.orders().get(0).orderNo(),
                recovered.orders().get(0).orderNo()
        );
    }

    private SubmitOrderResponse submitAfterSignal(
            CountDownLatch start,
            OrderService orderService,
            String idempotencyKey,
            SubmitOrderRequest request
    ) {
        await(start);
        return orderService.submitOrder(
                USER_ID,
                idempotencyKey,
                request
        );
    }

    private OrderService orderService(StringRedisTemplate redisTemplate) {
        return new OrderService(
                redisTemplate,
                new ObjectMapper().findAndRegisterModules(),
                transactionService,
                context.getBean(MallOrderMapper.class),
                context.getBean(OrderItemMapper.class)
        );
    }

    private int deductAfterSignal(CountDownLatch start) {
        await(start);
        Integer affected = transactionTemplate.execute(
                status -> productSkuMapper.deductAvailableStock(SKU_ID, 1)
        );
        return affected == null ? 0 : affected;
    }

    private <T> List<T> runConcurrently(
            Callable<T> first,
            Callable<T> second,
            CountDownLatch start
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<T> firstFuture = executor.submit(first);
            Future<T> secondFuture = executor.submit(second);
            start.countDown();
            return List.of(
                    firstFuture.get(10, TimeUnit.SECONDS),
                    secondFuture.get(10, TimeUnit.SECONDS)
            );
        } finally {
            executor.shutdownNow();
        }
    }

    private void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发测试等待被中断", interruptedException);
        }
    }

    private SubmitOrderRequest request(SubmitOrderItemRequest... items) {
        return new SubmitOrderRequest(ADDRESS_ID, List.of(items));
    }

    private void insertSpu(Long spuId, Long merchantId, int status) {
        jdbcTemplate.update("""
                INSERT INTO product_spu (id, merchant_id, title, status)
                VALUES (?, ?, ?, ?)
                """, spuId, merchantId, "测试商品-" + spuId, status);
    }

    private void insertSku(
            Long skuId,
            Long spuId,
            Long priceCent,
            int stock,
            int status
    ) {
        jdbcTemplate.update("""
                INSERT INTO product_sku (
                    id, spu_id, specs_json, price_cent, stock, locked_stock, version, status
                ) VALUES (?, ?, '{}', ?, ?, 0, 0, ?)
                """, skuId, spuId, priceCent, stock, status);
    }

    private int stock(Long skuId) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT stock FROM product_sku WHERE id = ?",
                Integer.class,
                skuId
        );
        return value == null ? -1 : value;
    }

    private long count(String table) {
        // table 只由测试内部常量调用，不能接收外部输入。
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table,
                Long.class
        );
        return value == null ? 0L : value;
    }

    /** 仅装配订单测试需要的 Mapper、事务管理器和被测 Service。 */
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class IntegrationConfiguration {

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                    MYSQL.getJdbcUrl(),
                    MYSQL.getUsername(),
                    MYSQL.getPassword()
            );
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(UserAddressMapper.class);
            configuration.addMapper(ProductSkuMapper.class);
            configuration.addMapper(ProductSpuMapper.class);
            configuration.addMapper(MallOrderMapper.class);
            configuration.addMapper(OrderItemMapper.class);
            configuration.addMapper(OrderSubmitRecordMapper.class);

            GlobalConfig globalConfig = new GlobalConfig();
            globalConfig.setDbConfig(new GlobalConfig.DbConfig());

            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            factory.setGlobalConfig(globalConfig);
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        UserAddressMapper userAddressMapper(SqlSessionTemplate template) {
            return template.getMapper(UserAddressMapper.class);
        }

        @Bean
        ProductSkuMapper productSkuMapper(SqlSessionTemplate template) {
            return template.getMapper(ProductSkuMapper.class);
        }

        @Bean
        ProductSpuMapper productSpuMapper(SqlSessionTemplate template) {
            return template.getMapper(ProductSpuMapper.class);
        }

        @Bean
        MallOrderMapper mallOrderMapper(SqlSessionTemplate template) {
            return template.getMapper(MallOrderMapper.class);
        }

        @Bean
        OrderItemMapper orderItemMapper(SqlSessionTemplate template) {
            return template.getMapper(OrderItemMapper.class);
        }

        @Bean
        OrderSubmitRecordMapper orderSubmitRecordMapper(SqlSessionTemplate template) {
            return template.getMapper(OrderSubmitRecordMapper.class);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        MqProperties mqProperties() {
            return new MqProperties();
        }

        @Bean
        MqOutboxService outboxService() {
            return mock(MqOutboxService.class);
        }

        @Bean
        ProductDetailCacheService productDetailCacheService() {
            return mock(ProductDetailCacheService.class);
        }

        @Bean
        OrderTransactionService orderTransactionService(
                UserAddressMapper userAddressMapper,
                ProductSkuMapper productSkuMapper,
                ProductSpuMapper productSpuMapper,
                MallOrderMapper mallOrderMapper,
                OrderItemMapper orderItemMapper,
                OrderSubmitRecordMapper orderSubmitRecordMapper,
                ObjectMapper objectMapper,
                MqProperties mqProperties,
                MqOutboxService outboxService,
                ProductDetailCacheService productDetailCacheService
        ) {
            return new OrderTransactionService(
                    userAddressMapper,
                    productSkuMapper,
                    productSpuMapper,
                    mallOrderMapper,
                    orderItemMapper,
                    orderSubmitRecordMapper,
                    objectMapper,
                    mqProperties,
                    outboxService,
                    productDetailCacheService
            );
        }
    }
}
