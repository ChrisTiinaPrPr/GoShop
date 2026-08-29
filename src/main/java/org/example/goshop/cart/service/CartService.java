package org.example.goshop.cart.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.util.Json;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.example.goshop.cart.dto.AddCartItemRequest;
import org.example.goshop.cart.dto.CartItemResponse;
import org.example.goshop.cart.dto.CartRedisItem;
import org.example.goshop.cart.dto.UpdateCartItemRequest;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.product.entity.ProductImage;
import org.example.goshop.product.entity.ProductSku;
import org.example.goshop.product.entity.ProductSpu;
import org.example.goshop.product.mapper.ProductSkuMapper;
import org.example.goshop.product.mapper.ProductSpuMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.time.Duration;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CartService {

    private static final String CART_KEY_PREFIX = "cart:";
    /**
     * Agent 动作 Redis 幂等标记保留时间。
     *
     * <p>agent_action 数据库状态是长期事实来源；
     * Redis 标记主要保护“购物车已经写入，但数据库动作尚未完成更新”
     * 这一短暂窗口。</p>
     */
    private static final Duration AGENT_ACTION_MARKER_TTL = Duration.ofDays(7);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final ProductSkuMapper productSkuMapper;
    private final ProductSpuMapper productSpuMapper;

    /**
     * 已通过购物车写入前校验的商品和 SKU。
     */
    private record PurchasableCartProduct(
            ProductSku sku,
            ProductSpu spu,
            int availableStock
    ) {
    }

    public List<CartItemResponse> listCurrentUserCartItems(Long userId) {
        String cartKey = CART_KEY_PREFIX + userId;

        // 从 Redis 中获取当前用户购物车数据，filed 为 SKU ID，value为 SKU 数量和选中状态的 JSON
        // "{\"quantity\":2,\"selected\":true}"：购物车状态 JSON
        Map<Object, Object> rawItems = stringRedisTemplate.opsForHash().entries(cartKey);

        if (rawItems.isEmpty()) {
            return List.of();
        }

        // TreeMap 使接口结果稳定按 SKU ID 排序
        Map<Long, CartRedisItem> cartItems = new TreeMap<>();

        // 遍历 Redis 中获取的购物车数据
        for (Map.Entry<Object, Object> entry : rawItems.entrySet()) {
            try {
                Long skuId = Long.valueOf(String.valueOf(entry.getKey()));
                // 将 Redis 中获取的 JSON 转为 CartRedisItem
                CartRedisItem item = objectMapper.readValue(
                        String.valueOf(entry.getValue()),
                        CartRedisItem.class
                );

                if (item.quantity() == null || item.quantity() <= 0) {
                    continue;
                }

                cartItems.put(skuId, item);
            } catch (NumberFormatException | JsonProcessingException ignored) {
                // Redis 中的异常历史数据不影响其他购物车项查询；
                // 后续“清理失效购物车项”接口可统一删除。
            }
        }
        if (cartItems.isEmpty()) {
            return List.of();
        }
        // 根据购物车中的全部 SKU ID，一次性从表中查询对应的 SKU 数据。
        // cartItems.keySet() --购物车中所有 SKU ID 的集合
        List<ProductSku> skus = productSkuMapper.selectList(
                new LambdaQueryWrapper<ProductSku>().in(ProductSku::getId, cartItems.keySet())
        );
        // 将 List<ProductSku> 转为一个以 SKU ID 为 key 的 Map，方便以后按购物车中的 SKU ID 快速查找
        // Function.identity() 表示将对象本身作为 MAP 的 value
        Map<Long,ProductSku> skuMap = skus.stream().collect(Collectors.toMap(ProductSku::getId, Function.identity()));

        Set<Long> spuIds = skus.stream().map(ProductSku::getSpuId).collect(Collectors.toSet());
        Map<Long,ProductSpu> spuMap;
        if (spuIds.isEmpty()) {
            // Map.of()用于快速创建一个空的不可修改的 Map
            spuMap = Map.of();
        } else {
            spuMap = productSpuMapper.selectList(
                    new LambdaQueryWrapper<ProductSpu>().in(ProductSpu::getId, spuIds)
            ).stream().collect(Collectors.toMap(ProductSpu::getId, Function.identity()));
        }
        // entrySet() -- 获取 Map 中的包含所有 entry 的Set
        return cartItems.entrySet().stream().map(
                entry -> buildCartItemResponse(
                        entry.getKey(),
                        entry.getValue(),
                        skuMap.get(entry.getKey()),
                        spuMap
                )
        ).toList();
    }

    private CartItemResponse buildCartItemResponse(
            Long skuId,
            CartRedisItem cartItem,
            ProductSku sku,
            Map<Long, ProductSpu> spuMap
    ) {
        if (sku == null) {
            return new CartItemResponse(
                    skuId,null,null,null,null,null,null,
                    cartItem.quantity(),cartItem.selected(),0,false,"SKU_NOT_FOUND"
            );
        }

        ProductSpu spu = spuMap.get(sku.getSpuId());

        int stock = sku.getStock() == null ? 0 : sku.getStock();
        int lockedStock = sku.getLockedStock() == null ? 0 : sku.getLockedStock();
        int availableStock = Math.max(stock - lockedStock,0);

        if (spu == null) {
            return new CartItemResponse(
                    sku.getId(),sku.getSpuId(),null,null,null,
                    sku.getSpecsJson(),sku.getPriceCent(),
                    cartItem.quantity(),cartItem.selected(),availableStock,false,"PRODUCT_NOT_FOUND"
            );
        }
        String status = "NORMAL";
        boolean valid = true;

        if (spu.getStatus() == null || spu.getStatus() != 1) {
            status = "PRODUCT_OFF_SHELF";
            valid = false;
        } else if (sku.getStatus() == null || sku.getStatus() != 1) {
            status = "SKU_DISABLED";
            valid = false;
        } else if (availableStock == 0) {
            status = "OUT_OF_STOCK";
            valid = false;
        } else if (cartItem.quantity() > availableStock) {
            status = "INSUFFICIENT_STOCK";
            valid = false;
        }

        return new CartItemResponse(
                sku.getId(),
                spu.getId(),
                spu.getMerchantId(),
                spu.getTitle(),
                spu.getMainImage(),
                sku.getSpecsJson(),
                sku.getPriceCent(),
                cartItem.quantity(),
                cartItem.selected(),
                availableStock,
                valid,
                status
        );
    }

    private static final DefaultRedisScript<Long> ADD_CART_ITEM_SCRIPT =
            new DefaultRedisScript<>("""
                local raw = redis.call('HGET', KEYS[1], ARGV[1])
                local totalQuantity = tonumber(ARGV[2])
                local selected = true

                if raw then
                    local item = cjson.decode(raw)
                    totalQuantity = totalQuantity + (tonumber(item.quantity) or 0)

                    if item.selected ~= nil then
                        selected = item.selected
                    end
                end

                if totalQuantity > tonumber(ARGV[3]) then
                    return -1
                end

                redis.call(
                    'HSET',
                    KEYS[1],
                    ARGV[1],
                    cjson.encode({
                        quantity = totalQuantity,
                        selected = selected
                    })
                )

                return totalQuantity
                """, Long.class);

    /**
     * Agent 确认动作专用的幂等加购脚本。
     *
     * <p>同时使用两种幂等维度：</p>
     *
     * <ul>
     *     <li>actionId：同一个动作只能执行一次；</li>
     *     <li>Idempotency-Key：同一用户的同一个确认请求键只能执行一个动作。</li>
     * </ul>
     *
     * <p>KEYS：</p>
     *
     * <ol>
     *     <li>购物车 Hash Key；</li>
     *     <li>actionId 执行标记；</li>
     *     <li>Idempotency-Key 执行标记。</li>
     * </ol>
     */
    private static final DefaultRedisScript<Long>
            ADD_AGENT_ACTION_CART_ITEM_SCRIPT =
            new DefaultRedisScript<>("""
                -- 相同 actionId 已经执行过：返回第一次执行后的数量。
                local processedQuantity =
                    redis.call('GET', KEYS[2])

                if processedQuantity then
                    return tonumber(processedQuantity)
                end

                -- 相同 Idempotency-Key 已经被另一个动作使用。
                -- 返回 -2，由 Java 转成 40903 动作状态冲突。
                local processedActionId =
                    redis.call('GET', KEYS[3])

                if processedActionId then
                    return -2
                end

                local raw =
                    redis.call('HGET', KEYS[1], ARGV[1])

                local totalQuantity =
                    tonumber(ARGV[2])

                local selected = true

                if raw then
                    local item = cjson.decode(raw)

                    totalQuantity =
                        totalQuantity
                        + (tonumber(item.quantity) or 0)

                    if item.selected ~= nil then
                        selected = item.selected
                    end
                end

                if totalQuantity > tonumber(ARGV[3]) then
                    return -1
                end

                redis.call(
                    'HSET',
                    KEYS[1],
                    ARGV[1],
                    cjson.encode({
                        quantity = totalQuantity,
                        selected = selected
                    })
                )

                -- actionId 标记保存第一次执行后的购物车数量。
                redis.call(
                    'SET',
                    KEYS[2],
                    tostring(totalQuantity),
                    'EX',
                    ARGV[4]
                )

                -- 请求幂等标记保存使用该 Key 的 actionId。
                redis.call(
                    'SET',
                    KEYS[3],
                    ARGV[5],
                    'EX',
                    ARGV[4]
                )

                return totalQuantity
                """, Long.class);

    /**
     * 普通买家接口加入购物车。
     */
    public CartItemResponse addCurrentUserCartItem(
            Long userId,
            AddCartItemRequest request
    ) {
        validateAddCartArguments(
                userId,
                request
        );

        PurchasableCartProduct product =
                requirePurchasableCartProduct(
                        request.skuId()
                );

        ProductSku sku = product.sku();
        ProductSpu spu = product.spu();

        String cartKey =
                CART_KEY_PREFIX + userId;

        Long totalQuantity =
                stringRedisTemplate.execute(
                        ADD_CART_ITEM_SCRIPT,
                        List.of(cartKey),
                        String.valueOf(
                                request.skuId()
                        ),
                        String.valueOf(
                                request.quantity()
                        ),
                        String.valueOf(
                                product.availableStock()
                        )
                );

        if (totalQuantity == null) {
            throw new BusinessException(
                    50000,
                    "添加购物车失败"
            );
        }

        if (totalQuantity == -1L) {
            throw new BusinessException(
                    40901,
                    "商品库存不足"
            );
        }

        CartRedisItem cartItem =
                getCartRedisItem(
                        cartKey,
                        request.skuId()
                );

        if (cartItem == null) {
            throw new BusinessException(
                    50000,
                    "购物车数据写入失败"
            );
        }

        return buildCartItemResponse(
                request.skuId(),
                cartItem,
                sku,
                Map.of(
                        spu.getId(),
                        spu
                )
        );
    }

    /**
     * 根据已经确认的 Agent actionId 幂等加入购物车。
     *
     * <p>该方法只能由 AgentActionService 调用，
     * 不能直接暴露为 Controller 接口。</p>
     *
     * <p>同一个 userId + actionId 无论调用多少次，
     * Redis Lua 脚本都只会累计一次购物车数量。</p>
     *
     * @param userId   当前 JWT 买家 ID
     * @param actionId 服务端 agent_action 主键
     * @param request  从 agent_action.payload_json 恢复的 SKU 和数量
     */
    public CartItemResponse
    addCurrentUserCartItemForAgentAction(
            Long userId,
            Long actionId,
            String idempotencyKey,
            AddCartItemRequest request
    ) {
        validateAddCartArguments(
                userId,
                request
        );

        /*
         * actionId 只能来自服务端已经锁定并校验归属的 AgentAction。
         */
        if (actionId == null || actionId <= 0) {
            throw new BusinessException(
                    40001,
                    "Agent 动作 ID 不合法"
            );
        }

        validateAgentIdempotencyKey(idempotencyKey);

        PurchasableCartProduct product =
                requirePurchasableCartProduct(
                        request.skuId()
                );

        ProductSku sku = product.sku();
        ProductSpu spu = product.spu();

        String cartKey =
                CART_KEY_PREFIX + userId;

        /*
         * Redis Cluster 要求一个 Lua 脚本涉及的 Key 位于同一个 Slot。
         *
         * 普通购物车 Key 为 cart:{userId 的值}，例如 cart:123。
         * 标记 Key 中使用 {cart:123} 作为 Hash Tag，
         * 因而它与原购物车 Key 的哈希内容一致。
         */
        String actionMarkerKey =
                "agent-action:{"
                        + cartKey
                        + "}:"
                        + actionId;

        /*
         * Idempotency-Key 已经经过字符白名单和长度校验，
         * 可以安全作为 Redis Key 的一部分。
         *
         * {cartKey} Hash Tag 保证三个 Key 位于相同 Redis Cluster Slot。
         */
        String requestMarkerKey =
                "agent-idempotency:{"
                        + cartKey
                        + "}:"
                        + idempotencyKey.strip();

        Long totalQuantity =
                stringRedisTemplate.execute(
                        ADD_AGENT_ACTION_CART_ITEM_SCRIPT,
                        List.of(
                                cartKey,
                                actionMarkerKey,
                                requestMarkerKey
                        ),
                        String.valueOf(
                                request.skuId()
                        ),
                        String.valueOf(
                                request.quantity()
                        ),
                        String.valueOf(
                                product.availableStock()
                        ),
                        String.valueOf(
                                AGENT_ACTION_MARKER_TTL
                                        .toSeconds()
                        ),
                        /*
                         * ARGV[5] 只用于记录哪个 actionId 占用了请求幂等键。
                         */
                        String.valueOf(actionId)

                );

        if (totalQuantity == null) {
            throw new BusinessException(
                    50000,
                    "Agent 加入购物车失败"
            );
        }

        if (totalQuantity == -1L) {
            throw new BusinessException(
                    40901,
                    "商品库存不足"
            );
        }

        if (totalQuantity == -2L) {
            throw new BusinessException(
                    40903,
                    "Idempotency-Key 已用于其他动作"
            );
        }

        /*
         * 无论本次是首次执行还是幂等重放，
         * 都从 Redis 读取当前购物车项构造业务响应。
         */
        CartRedisItem cartItem =
                getCartRedisItem(
                        cartKey,
                        request.skuId()
                );

        if (cartItem == null) {
            throw new BusinessException(
                    50000,
                    "购物车数据写入失败"
            );
        }

        return buildCartItemResponse(
                request.skuId(),
                cartItem,
                sku,
                Map.of(
                        spu.getId(),
                        spu
                )
        );
    }

    /**
     * 校验购物车加购公共参数。
     *
     * <p>Controller 的 Bean Validation 不能替代 Service 校验，
     * 因为 Agent 确认流程会直接调用 Service。</p>
     */
    private void validateAddCartArguments(
            Long userId,
            AddCartItemRequest request
    ) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(
                    40001,
                    "用户 ID 不合法"
            );
        }

        if (request == null
                || request.skuId() == null
                || request.skuId() <= 0) {
            throw new BusinessException(
                    40001,
                    "SKU ID 必须为正数"
            );
        }

        if (request.quantity() == null
                || request.quantity() <= 0) {
            throw new BusinessException(
                    40001,
                    "购买数量必须为正数"
            );
        }
    }

    /**
     * 查询并校验当前可加入购物车的商品。
     *
     * <p>普通购物车接口和 Agent 确认接口必须复用同一套业务规则。</p>
     */
    private PurchasableCartProduct
    requirePurchasableCartProduct(
            Long skuId
    ) {
        ProductSku sku =
                productSkuMapper.selectById(skuId);

        if (sku == null) {
            throw new BusinessException(
                    40401,
                    "SKU 不存在"
            );
        }

        ProductSpu spu =
                productSpuMapper.selectById(
                        sku.getSpuId()
                );

        if (spu == null
                || !Integer.valueOf(1).equals(
                spu.getStatus()
        )) {
            throw new BusinessException(
                    40901,
                    "商品不存在或已下架"
            );
        }

        if (!Integer.valueOf(1).equals(
                sku.getStatus()
        )) {
            throw new BusinessException(
                    40901,
                    "SKU 已禁用"
            );
        }

        int stock =
                sku.getStock() == null
                        ? 0
                        : sku.getStock();

        int lockedStock =
                sku.getLockedStock() == null
                        ? 0
                        : sku.getLockedStock();

        int availableStock =
                Math.max(
                        stock - lockedStock,
                        0
                );

        if (availableStock <= 0) {
            throw new BusinessException(
                    40901,
                    "商品已售罄"
            );
        }

        return new PurchasableCartProduct(
                sku,
                spu,
                availableStock
        );
    }

    private CartRedisItem getCartRedisItem(String cartKey,Long skuId) {
        Object rawValue = stringRedisTemplate.opsForHash().get(cartKey,String.valueOf(skuId));

        if (rawValue == null) {
            return null;
        }

        try {
            return objectMapper.readValue(
                    String.valueOf(rawValue),
                    CartRedisItem.class
            );
        } catch (JsonProcessingException e) {
            throw new BusinessException(50000, "购物车数据异常");
        }
    }

    private static final DefaultRedisScript<Long> UPDATE_CART_ITEM_SCRIPT =
            new DefaultRedisScript<>("""
                local raw = redis.call('HGET', KEYS[1], ARGV[1])

                -- 购物车中不存在该 SKU。
                if not raw then
                    return -2
                end

                local item = cjson.decode(raw)
                local quantity = tonumber(item.quantity) or 0
                local selected = item.selected

                if selected == nil then
                    selected = true
                end

                -- quantity 为空字符串表示保持原数量。
                if ARGV[2] ~= '' then
                    quantity = tonumber(ARGV[2])
                end

                -- selected 为空字符串表示保持原勾选状态。
                if ARGV[3] ~= '' then
                    selected = ARGV[3] == 'true'
                end

                -- 仅修改数量时才传入可用库存上限。
                if ARGV[4] ~= '' and quantity > tonumber(ARGV[4]) then
                    return -1
                end

                redis.call(
                    'HSET',
                    KEYS[1],
                    ARGV[1],
                    cjson.encode({
                        quantity = quantity,
                        selected = selected
                    })
                )

                return quantity
                """, Long.class);

    public CartItemResponse updateCurrentUserCartItem(
            Long userId,
            Long skuId,
            UpdateCartItemRequest request
    ) {
        if (request.quantity() == null && request.selected() == null) {
            throw new BusinessException(40001, "至少提供 quantity 或 selected其中一个字段");
        }

        ProductSku sku = null;
        ProductSpu spu = null;
        int availableStock = 0;

        // 只有修改数量时才需要校验商品当前是否可购买
        if (request.quantity() != null) {
            sku = productSkuMapper.selectById(skuId);
            if (sku == null) {
                throw new BusinessException(40401, "SKU 不存在");
            }

            spu = productSpuMapper.selectById(sku.getSpuId());
            if (spu == null || spu.getStatus() == null || spu.getStatus() != 1) {
                throw new BusinessException(40901, "商品不存在或已下架");
            }

            if (sku.getStatus() == null || sku.getStatus() != 1) {
                throw new BusinessException(40901, "SKU 已禁用");
            }

            int stock = sku.getStock() == null ? 0 : sku.getStock();
            int lockedStock = sku.getLockedStock() == null ? 0 : sku.getLockedStock();
            availableStock = Math.max(stock - lockedStock,0);

            if (availableStock <= 0) {
                throw new BusinessException(40901, "商品已售罄");
            }
        }

        String cartKey = CART_KEY_PREFIX + userId;
        // 执行 Lua 脚本，原子地修改购物车数据，返回正数：修改后的购物车数量。返回-1：商品库存不足。返回-2：购物车中不存在该 SKU。返回null：脚本执行异常修改失败。
        Long result = stringRedisTemplate.execute(
                UPDATE_CART_ITEM_SCRIPT,
                List.of(cartKey),
                String.valueOf(skuId),
                request.quantity() == null ? "" : String.valueOf(request.quantity()),
                request.selected() == null ? "" : String.valueOf(request.selected()),
                request.quantity() == null ? "" : String.valueOf(availableStock)
        );
        if (result == null) {
            throw new BusinessException(50000, "修改购物车失败");
        }

        if (result == -2L) {
            throw new BusinessException(40401, "购物车中不存在该商品");
        }

        if (result == -1L) {
            throw new BusinessException(40901, "商品库存不足");
        }

        CartRedisItem cartItem = getCartRedisItem(cartKey,skuId);
        if (cartItem == null) {
            throw new BusinessException(50000, "购物车数据读取失败");
        }

        // 仅修改勾选状态时，此前未查询的商品；这里再查询以构造最新相应
        if (sku == null) {
            sku = productSkuMapper.selectById(skuId);
        }

        Map<Long,ProductSpu> spuMap = Map.of();
        if (sku == null) {
            if (spu == null) {
                spu = productSpuMapper.selectById(sku.getSpuId());
            }

            if (spu != null) {
                spuMap = Map.of(spu.getId(),spu);
            }
        }
        return buildCartItemResponse(skuId,cartItem,sku,spuMap);
    }

    public void deleteCurrentUserCartItem(Long userId, Long skuId) {
        String cartKey = CART_KEY_PREFIX + userId;

        Long deleteCount = stringRedisTemplate.opsForHash().delete(
                cartKey,
                String.valueOf(skuId)
        );

        if (deleteCount == null || deleteCount == 0) {
            throw new BusinessException(40401, "购物车中不存在该商品");
        }
        // 删除最后一个 Hash field 后，Redis 会自动删除空 Hash key，无需额外处理。
    }

    public void clearInvalidCurrentUserCartItems(Long userId) {
        String cartKey = CART_KEY_PREFIX + userId;

        Map<Object,Object> rawItems = stringRedisTemplate.opsForHash().entries(cartKey);
        if (rawItems == null || rawItems.isEmpty()) {
            return;
        }

        Set<String> fieldsToDelete = new HashSet<>();
        Map<Long,String> skuFieldMap = new HashMap<>();

        for (Map.Entry<Object,Object> entry : rawItems.entrySet()) {
            String field = String.valueOf(entry.getKey());

            try {
                Long skuId = Long.valueOf(field);

                CartRedisItem cartItem = objectMapper.readValue(
                        String.valueOf(entry.getValue()),
                        CartRedisItem.class
                );

                // Redis 脏数据或无效数据直接视为无效项.
                if (cartItem.quantity() == null || cartItem.quantity() <= 0) {
                    fieldsToDelete.add(field);
                    continue;
                }
                skuFieldMap.put(skuId,field);
            } catch (NumberFormatException | JsonProcessingException e) {
                // 无法解析的 field/value 无法继续使用，直接清理
                fieldsToDelete.add(field);
            }
        }

        if (!fieldsToDelete.isEmpty()) {
            List<ProductSku> skus = productSkuMapper.selectList(
                    new LambdaQueryWrapper<ProductSku>().in(ProductSku::getId,skuFieldMap.keySet())
            );

            Map<Long,ProductSku> skuMap = skus.stream().collect(Collectors.toMap(ProductSku::getId,Function.identity()));
            Set<Long> spuIds = skus.stream().map(ProductSku::getSpuId).collect(Collectors.toSet());

            // 吧查询到的商品 SPU 列表转换成一个以 SPU ID 为 key 的 Map，方便后续按 ID 快速查找商品。
            Map<Long,ProductSpu> spuMap = spuIds.isEmpty()
                    ? Map.of()
                    : productSpuMapper.selectList(
                            new LambdaQueryWrapper<ProductSpu>()
                                    .in(ProductSpu::getId,spuIds)
            ).stream().collect(Collectors.toMap(ProductSpu::getId,Function.identity()));

            for (Map.Entry<Long,String> entry : skuFieldMap.entrySet()) {
                Long skuId = entry.getKey();
                String redisField = entry.getValue();

                ProductSku sku = skuMap.get(skuId);
                if (sku == null) {
                    fieldsToDelete.add(redisField);
                    continue;
                }

                ProductSpu spu = spuMap.get(sku.getSpuId());

                int stock = sku.getStock() == null ? 0 : sku.getStock();
                int lockedStock = sku.getLockedStock() == null ? 0 : sku.getLockedStock();
                int availableStock = Math.max(stock - lockedStock,0);

                boolean invalid = spu == null
                        || spu.getStatus() == null
                        || spu.getStatus() != 1
                        || sku.getStatus() == null
                        || sku.getStatus() != 1
                        || availableStock <= 0;

                if (invalid) {
                    fieldsToDelete.add(redisField);
                }
            }
        }
        if (!fieldsToDelete.isEmpty()) {
            stringRedisTemplate.opsForHash().delete(cartKey,fieldsToDelete.toArray());
        }
    }

    /**
     * 校验 Agent 确认请求的幂等键。
     *
     * <p>规则与订单接口保持一致，只允许字母、数字、下划线和短横线。</p>
     */
    private void validateAgentIdempotencyKey(
            String idempotencyKey
    ) {
        if (idempotencyKey == null
                || idempotencyKey.isBlank()) {
            throw new BusinessException(
                    40001,
                    "Idempotency-Key 不能为空"
            );
        }

        String normalized =
                idempotencyKey.strip();

        if (normalized.length() > 64
                || !normalized.matches(
                "^[A-Za-z0-9_-]+$"
        )) {
            throw new BusinessException(
                    40001,
                    "Idempotency-Key 格式不合法"
            );
        }
    }
}
