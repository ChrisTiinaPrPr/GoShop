package org.example.goshop.order.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.example.goshop.infrastructure.mq.MqProperties;
import org.example.goshop.infrastructure.mq.outbox.MqOutboxService;
import org.example.goshop.order.dto.SubmitOrderItemRequest;
import org.example.goshop.order.dto.SubmitOrderRequest;
import org.example.goshop.order.dto.SubmitOrderResponse;
import org.example.goshop.order.mapper.MallOrderMapper;
import org.example.goshop.order.mapper.OrderItemMapper;
import org.example.goshop.order.mapper.OrderSubmitRecordMapper;
import org.example.goshop.product.cache.ProductDetailCacheService;
import org.example.goshop.product.entity.ProductSku;
import org.example.goshop.product.entity.ProductSpu;
import org.example.goshop.product.mapper.ProductSkuMapper;
import org.example.goshop.product.mapper.ProductSpuMapper;
import org.example.goshop.user.entity.UserAddress;
import org.example.goshop.user.mapper.UserAddressMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 下单事务的金额聚合回归测试。
 *
 * <p>数据库访问通过 Mockito 隔离，测试重点是服务端按商家拆单后，
 * 返回的总应付金额只能把每张拆分订单累计一次。</p>
 */
class OrderTransactionServiceTest {

    private static final Long USER_ID = 1001L;
    private static final Long ADDRESS_ID = 2001L;

    private UserAddressMapper userAddressMapper;
    private ProductSkuMapper productSkuMapper;
    private ProductSpuMapper productSpuMapper;
    private OrderTransactionService orderTransactionService;

    @BeforeEach
    void setUp() {
        // 纯单元测试不启动 MyBatis，上层服务构造 LambdaQueryWrapper 前需先注册实体字段元数据。
        initializeTableInfo(UserAddress.class);
        initializeTableInfo(ProductSku.class);
        initializeTableInfo(ProductSpu.class);

        userAddressMapper = mock(UserAddressMapper.class);
        productSkuMapper = mock(ProductSkuMapper.class);
        productSpuMapper = mock(ProductSpuMapper.class);

        orderTransactionService = new OrderTransactionService(
                userAddressMapper,
                productSkuMapper,
                productSpuMapper,
                mock(MallOrderMapper.class),
                mock(OrderItemMapper.class),
                mock(OrderSubmitRecordMapper.class),
                new ObjectMapper(),
                new MqProperties(),
                mock(MqOutboxService.class),
                mock(ProductDetailCacheService.class)
        );
    }

    /**
     * 两个商家的商品会生成两张订单；总应付金额应为 2×100 + 3×150 = 650 分，
     * 不能在遍历拆分订单时重复累加成 1300 分。
     */
    @Test
    void shouldAddEachSplitOrderAmountToTotalOnlyOnce() {
        ProductSku firstSku = sku(3001L, 4001L, 100L);
        ProductSku secondSku = sku(3002L, 4002L, 150L);
        ProductSpu firstSpu = spu(4001L, 5001L);
        ProductSpu secondSpu = spu(4002L, 5002L);

        when(userAddressMapper.selectOne(any())).thenReturn(address());
        when(productSkuMapper.selectList(any()))
                .thenReturn(List.of(firstSku, secondSku));
        when(productSpuMapper.selectList(any()))
                .thenReturn(List.of(firstSpu, secondSpu));
        when(productSkuMapper.deductAvailableStock(anyLong(), any()))
                .thenReturn(1);

        SubmitOrderResponse response = orderTransactionService.createOrders(
                USER_ID,
                new SubmitOrderRequest(
                        ADDRESS_ID,
                        List.of(
                                new SubmitOrderItemRequest(firstSku.getId(), 2),
                                new SubmitOrderItemRequest(secondSku.getId(), 3)
                        )
                )
        );

        assertEquals(2, response.orders().size());
        assertEquals(650L, response.totalPaymentAmountCent());
        assertEquals(
                response.totalPaymentAmountCent(),
                response.orders().stream()
                        .mapToLong(order -> order.payAmountCent())
                        .sum()
        );
    }

    /** 单商家订单的总应付金额也必须等于服务端按单价和数量计算的拆分订单金额。 */
    @Test
    void shouldCalculateSingleMerchantTotalFromServerPrices() {
        ProductSku firstSku = sku(3001L, 4001L, 125L);
        ProductSku secondSku = sku(3002L, 4002L, 250L);
        ProductSpu firstSpu = spu(4001L, 5001L);
        ProductSpu secondSpu = spu(4002L, 5001L);

        when(userAddressMapper.selectOne(any())).thenReturn(address());
        when(productSkuMapper.selectList(any()))
                .thenReturn(List.of(firstSku, secondSku));
        when(productSpuMapper.selectList(any()))
                .thenReturn(List.of(firstSpu, secondSpu));
        when(productSkuMapper.deductAvailableStock(anyLong(), any()))
                .thenReturn(1);

        SubmitOrderResponse response = orderTransactionService.createOrders(
                USER_ID,
                new SubmitOrderRequest(
                        ADDRESS_ID,
                        List.of(
                                new SubmitOrderItemRequest(firstSku.getId(), 2),
                                new SubmitOrderItemRequest(secondSku.getId(), 1)
                        )
                )
        );

        assertEquals(1, response.orders().size());
        assertEquals(500L, response.totalPaymentAmountCent());
        assertEquals(500L, response.orders().get(0).payAmountCent());
    }

    private UserAddress address() {
        UserAddress address = new UserAddress();
        address.setId(ADDRESS_ID);
        address.setUserId(USER_ID);
        address.setReceiver("测试用户");
        address.setPhone("13800000000");
        address.setProvince("广东省");
        address.setCity("深圳市");
        address.setDistrict("南山区");
        address.setDetail("测试路 1 号");
        return address;
    }

    private ProductSku sku(Long id, Long spuId, Long priceCent) {
        ProductSku sku = new ProductSku();
        sku.setId(id);
        sku.setSpuId(spuId);
        sku.setPriceCent(priceCent);
        sku.setStatus(1);
        return sku;
    }

    private ProductSpu spu(Long id, Long merchantId) {
        ProductSpu spu = new ProductSpu();
        spu.setId(id);
        spu.setMerchantId(merchantId);
        spu.setTitle("测试商品-" + id);
        spu.setStatus(1);
        return spu;
    }

    private void initializeTableInfo(Class<?> entityType) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                configuration,
                entityType.getName()
        );
        TableInfoHelper.initTableInfo(assistant, entityType);
    }
}
