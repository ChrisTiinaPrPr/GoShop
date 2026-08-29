package org.example.goshop.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.goshop.agent.config.AgentProperties;
import org.example.goshop.agent.entity.AgentAction;
import org.example.goshop.agent.entity.AgentActionStatus;
import org.example.goshop.agent.entity.AgentActionType;
import org.example.goshop.agent.entity.AgentConversation;
import org.example.goshop.agent.mapper.AgentActionMapper;
import org.example.goshop.agent.mapper.AgentConversationMapper;
import org.example.goshop.agent.service.model.AgentAddCartActionPayload;
import org.example.goshop.agent.service.model.AgentAddCartActionProposal;
import org.example.goshop.agent.tool.product.AgentProductDetailResult;
import org.example.goshop.agent.tool.product.AgentProductQueryService;
import org.example.goshop.agent.tool.product.AgentProductSkuDetail;
import org.example.goshop.common.exception.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.example.goshop.agent.dto.AgentActionConfirmResponse;
import org.example.goshop.agent.dto.AgentActionStatusResponse;
import org.example.goshop.cart.dto.AddCartItemRequest;
import org.example.goshop.cart.dto.CartItemResponse;
import org.example.goshop.cart.service.CartService;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.regex.Pattern;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Agent 待确认动作业务 Service。
 *
 * <p>当前阶段只负责创建 ADD_CART_ITEM 的 PENDING 动作，
 * 不会修改购物车。</p>
 *
 * <p>后续确认接口会继续在这个 Service 中实现，但必须遵守：</p>
 *
 * <ul>
 *     <li>模型只能创建待确认动作；</li>
 *     <li>只有用户调用独立 REST 接口才能真正加购；</li>
 *     <li>确认时重新读取 payloadJson；</li>
 *     <li>确认时重新调用 CartService 检查 SKU 和库存。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "goshop.agent",
        name = "enabled",
        havingValue = "true"
)
public class AgentActionService {

    private static final int MAX_ACTION_QUANTITY = 99;

    /**
     * 确认请求幂等键字符白名单。
     */
    private static final Pattern IDEMPOTENCY_KEY_PATTERN =
            Pattern.compile(
                    "^[A-Za-z0-9_-]+$"
            );

    private final AgentProperties agentProperties;

    private final AgentActionMapper actionMapper;

    private final AgentConversationMapper conversationMapper;

    /**
     * 真正确认后才允许调用的购物车业务 Service。
     */
    private final CartService cartService;

    /**
     * 用编程式事务处理“先提交 EXPIRED，再返回 40903”的场景。
     */
    private final TransactionTemplate transactionTemplate;

    /**
     * 复用 Agent 商品查询门面。
     *
     * <p>该门面继续调用 ProductService，不会让动作 Service
     * 直接读取 ProductSpuMapper 或 ProductSkuMapper。</p>
     */

    private final AgentProductQueryService productQueryService;

    private final ObjectMapper objectMapper;

    /**
     * 创建一个等待用户确认的加购动作。
     *
     * <p>该方法执行完成后，购物车内容必须保持不变。</p>
     *
     * @param userId         当前买家 ID，只能来自 ToolContext
     * @param conversationId 当前 Agent 会话 ID，只能来自 ToolContext
     * @param productId      模型从商品查询结果中选择的商品 ID
     * @param skuId          模型从商品详情结果中选择的 SKU ID
     * @param quantity       建议加购数量
     */
    @Transactional
    public AgentAddCartActionProposal
    createPendingAddCartAction(
            Long userId,
            Long conversationId,
            Long productId,
            Long skuId,
            Integer quantity
    ) {
        /*
         * 即使上层工具已经校验参数，Service 仍然需要执行自己的边界检查。
         */
        validateArguments(
                userId,
                conversationId,
                productId,
                skuId,
                quantity
        );

        /*
         * 锁定当前用户拥有的会话。
         *
         * 除了校验归属，这也能防止未来“删除会话”与
         * “创建待确认动作”同时进行，造成外键冲突。
         */
        AgentConversation conversation =
                conversationMapper
                        .selectOwnedConversationForUpdate(
                                conversationId,
                                userId
                        );

        if (conversation == null) {
            throw new BusinessException(
                    40401,
                    "Agent 会话不存在或无权访问"
            );
        }

        /*
         * 实时查询公开可售商品详情。
         *
         * 不能信任模型传入的商品标题、价格、图片和规格，
         * 因此创建动作时只接受 productId、skuId 和 quantity，
         * 其他确认卡片字段全部从服务端商品查询结果生成。
         */
        AgentProductDetailResult product =
                productQueryService.getDetail(
                        productId
                );

        /*
         * SKU 必须实际属于该商品，并且必须存在于公开可售 SKU 中。
         *
         * AgentProductQueryService 已经排除了：
         * 1. 已下架商品；
         * 2. 已禁用 SKU；
         * 3. 不公开的商品数据。
         */
        AgentProductSkuDetail selectedSku =
                product.skus()
                        .stream()
                        .filter(sku ->
                                Objects.equals(
                                        sku.skuId(),
                                        skuId
                                )
                        )
                        .findFirst()
                        .orElseThrow(() ->
                                new BusinessException(
                                        40901,
                                        "SKU 不可购买或不属于该商品"
                                )
                        );

        /*
         * 创建确认卡片时先检查一次实时库存，
         * 避免向用户展示一个已经无法执行的动作。
         *
         * 用户点击确认时仍然必须由 CartService 再检查一次，
         * 因为库存可能在这十分钟内变化。
         */
        if (selectedSku.availableStock()
                < quantity) {
            throw new BusinessException(
                    40901,
                    "商品库存不足"
            );
        }

        /*
         * 服务端根据实时商品数据生成不可篡改的动作载荷。
         */
        AgentAddCartActionPayload payload =
                new AgentAddCartActionPayload(
                        product.productId(),
                        selectedSku.skuId(),
                        quantity,
                        product.title(),
                        selectedSku.specifications(),
                        selectedSku.priceCent(),
                        product.mainImage()
                );

        String payloadJson =
                serializePayload(payload);

        /*
         * 项目当前 MySQL 使用 DATETIME(3)，Java 时间统一截断到毫秒。
         */
        LocalDateTime now =
                LocalDateTime.now()
                        .truncatedTo(
                                ChronoUnit.MILLIS
                        );

        LocalDateTime expiresAt =
                now.plusMinutes(
                        agentProperties
                                .actionTtlMinutes()
                );

        AgentAction action =
                new AgentAction();

        action.setConversationId(conversationId);
        action.setUserId(userId);
        action.setActionType(
                AgentActionType.ADD_CART_ITEM
        );
        action.setPayloadJson(payloadJson);
        action.setStatus(
                AgentActionStatus.PENDING
        );

        /*
         * 幂等键只在用户首次确认时写入。
         * 创建动作时必须为空。
         */
        action.setIdempotencyKey(null);
        action.setResultJson(null);
        action.setExpiresAt(expiresAt);
        action.setExecutedAt(null);
        action.setCreatedAt(now);
        action.setUpdatedAt(now);

        int inserted =
                actionMapper.insert(action);

        if (inserted != 1
                || action.getId() == null) {
            throw new BusinessException(
                    50000,
                    "创建待确认动作失败"
            );
        }

        /*
         * 返回给模型和 SSE 的内容来自刚刚保存的服务端快照，
         * 不使用模型生成的展示文字。
         */
        return new AgentAddCartActionProposal(
                action.getId(),
                action.getActionType().name(),
                action.getStatus().name(),
                payload.productId(),
                payload.skuId(),
                payload.quantity(),
                payload.productTitle(),
                payload.specifications(),
                payload.unitPriceCent(),
                payload.imageUrl(),

                /*
                 * 数据库当前按 JVM 本地时区写入 LocalDateTime。
                 * 转换成带时区含义的 Instant 后发送给前端。
                 */
                expiresAt
                        .atZone(
                                ZoneId.systemDefault()
                        )
                        .toInstant()
        );
    }

    /**
     * 再次校验动作创建参数。
     */
    private void validateArguments(
            Long userId,
            Long conversationId,
            Long productId,
            Long skuId,
            Integer quantity
    ) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException(
                    "动作缺少合法用户 ID"
            );
        }

        if (conversationId == null
                || conversationId <= 0) {
            throw new IllegalArgumentException(
                    "动作缺少合法会话 ID"
            );
        }

        if (productId == null || productId <= 0) {
            throw new IllegalArgumentException(
                    "商品 ID 必须为正数"
            );
        }

        if (skuId == null || skuId <= 0) {
            throw new IllegalArgumentException(
                    "SKU ID 必须为正数"
            );
        }

        if (quantity == null
                || quantity <= 0
                || quantity > MAX_ACTION_QUANTITY) {
            throw new IllegalArgumentException(
                    "Agent 加购数量必须在 1～99 之间"
            );
        }
    }

    /**
     * 序列化服务端生成的动作载荷。
     *
     * <p>序列化失败时不能保存部分 JSON，也不能回退为模型参数。</p>
     */
    private String serializePayload(
            AgentAddCartActionPayload payload
    ) {
        try {
            return objectMapper
                    .writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            /*
             * 不把商品标题、规格或 Jackson 异常正文写入日志或响应。
             */
            throw new BusinessException(
                    50000,
                    "生成待确认动作失败"
            );
        }
    }

    /**
     * 用户确认一个待执行的加购动作。
     *
     * <p>该方法由独立 REST 接口调用，不会被模型工具直接调用。</p>
     */
    public AgentActionConfirmResponse confirmAction(
            Long userId,
            Long actionId,
            String idempotencyKey
    ) {
        validateUserAndActionId(
                userId,
                actionId
        );

        String normalizedIdempotencyKey =
                normalizeIdempotencyKey(
                        idempotencyKey
                );

        /*
         * 事务闭包内部不使用“更新 EXPIRED 后立即抛异常”的写法。
         *
         * 它会先返回失败结果，让 TransactionTemplate 正常提交；
         * 事务提交后再由 unwrapResult() 抛出业务异常。
         */
        ActionOperationResult<
                AgentActionConfirmResponse
                > operationResult =
                transactionTemplate.execute(
                        status ->
                                confirmInTransaction(
                                        userId,
                                        actionId,
                                        normalizedIdempotencyKey
                                )
                );

        return unwrapResult(operationResult);
    }

    /**
     * 在数据库事务和动作行锁内执行确认。
     */
    private ActionOperationResult<
            AgentActionConfirmResponse
            > confirmInTransaction(
            Long userId,
            Long actionId,
            String idempotencyKey
    ) {
        /*
         * 锁定动作行，串行处理确认与取消。
         */
        AgentAction action =
                actionMapper.selectByIdForUpdate(
                        actionId
                );

        ActionOperationResult<
                AgentActionConfirmResponse
                > accessFailure =
                validateActionAccess(
                        action,
                        userId
                );

        if (accessFailure != null) {
            return accessFailure;
        }

        LocalDateTime now =
                currentDatabaseTime();

        /*
         * 已经确认且幂等键相同：
         * 直接返回第一次保存的结果，不调用 CartService。
         */
        if (action.getStatus()
                == AgentActionStatus.CONFIRMED) {
            if (Objects.equals(
                    action.getIdempotencyKey(),
                    idempotencyKey
            )) {
                return ActionOperationResult.success(
                        deserializeConfirmedResult(
                                action.getResultJson()
                        )
                );
            }

            return ActionOperationResult.failure(
                    40903,
                    "动作已经使用其他幂等键确认"
            );
        }

        if (action.getStatus()
                == AgentActionStatus.CANCELLED) {
            return ActionOperationResult.failure(
                    40903,
                    "动作已经取消"
            );
        }

        if (action.getStatus()
                == AgentActionStatus.EXPIRED) {
            return ActionOperationResult.failure(
                    40903,
                    "动作已经过期"
            );
        }

        if (action.getStatus()
                != AgentActionStatus.PENDING) {
            return ActionOperationResult.failure(
                    40903,
                    "动作状态不允许确认"
            );
        }

        /*
         * PENDING 只是数据库状态，还必须检查有效时间。
         */
        if (action.getExpiresAt() == null
                || !action.getExpiresAt()
                .isAfter(now)) {
            action.setStatus(
                    AgentActionStatus.EXPIRED
            );
            action.setUpdatedAt(now);

            updateActionOrThrow(action);

            /*
             * 返回失败结果而不是在事务内抛异常，
             * 使 EXPIRED 状态能够正常提交。
             */
            return ActionOperationResult.failure(
                    40903,
                    "动作已经过期"
            );
        }

        /*
         * 在修改购物车前检查数据库中是否已有其他动作使用该 Key。
         *
         * Redis Lua 脚本还会做一次并发保护。
         */
        AgentAction actionUsingIdempotencyKey =
                actionMapper
                        .selectByUserIdAndIdempotencyKey(
                                userId,
                                idempotencyKey
                        );

        if (actionUsingIdempotencyKey != null
                && !Objects.equals(
                actionUsingIdempotencyKey.getId(),
                action.getId()
        )) {
            return ActionOperationResult.failure(
                    40903,
                    "Idempotency-Key 已用于其他动作"
            );
        }

        /*
         * 确认接口不接收 skuId 和 quantity。
         *
         * 必须从创建动作时保存的 payloadJson 恢复，
         * 防止前端在确认请求中替换 SKU 或数量。
         */
        AgentAddCartActionPayload payload =
                deserializeActionPayload(
                        action.getPayloadJson()
                );

        /*
         * 只有这里才真正写购物车。
         *
         * CartService 会重新检查：
         * 1. SKU 是否存在；
         * 2. 商品是否上架；
         * 3. SKU 是否启用；
         * 4. 最新可用库存；
         * 5. actionId 与 Idempotency-Key 是否已经执行。
         */
        CartItemResponse cartItem =
                cartService
                        .addCurrentUserCartItemForAgentAction(
                                userId,
                                action.getId(),
                                idempotencyKey,
                                new AddCartItemRequest(
                                        payload.skuId(),
                                        payload.quantity()
                                )
                        );

        AgentActionConfirmResponse response =
                new AgentActionConfirmResponse(
                        action.getId(),
                        AgentActionStatus
                                .CONFIRMED
                                .name(),
                        cartItem
                );

        /*
         * 先完成响应序列化，再修改动作实体。
         *
         * 如果序列化失败，数据库事务回滚；
         * Redis actionId 标记仍能保证重试时不会重复累计购物车。
         */
        String resultJson =
                serializeConfirmedResult(
                        response
                );

        action.setStatus(
                AgentActionStatus.CONFIRMED
        );
        action.setIdempotencyKey(
                idempotencyKey
        );
        action.setResultJson(resultJson);
        action.setExecutedAt(now);
        action.setUpdatedAt(now);

        updateActionOrThrow(action);

        return ActionOperationResult.success(
                response
        );
    }

    /**
     * 用户取消一个待确认动作。
     *
     * <p>重复取消已经 CANCELLED 的动作直接返回当前状态，
     * 不重复更新数据库。</p>
     */
    public AgentActionStatusResponse cancelAction(
            Long userId,
            Long actionId
    ) {
        validateUserAndActionId(
                userId,
                actionId
        );

        ActionOperationResult<
                AgentActionStatusResponse
                > operationResult =
                transactionTemplate.execute(
                        status ->
                                cancelInTransaction(
                                        userId,
                                        actionId
                                )
                );

        return unwrapResult(operationResult);
    }

    /**
     * 在动作行锁内执行取消。
     */
    private ActionOperationResult<
            AgentActionStatusResponse
            > cancelInTransaction(
            Long userId,
            Long actionId
    ) {
        AgentAction action =
                actionMapper.selectByIdForUpdate(
                        actionId
                );

        ActionOperationResult<
                AgentActionStatusResponse
                > accessFailure =
                validateActionAccess(
                        action,
                        userId
                );

        if (accessFailure != null) {
            return accessFailure;
        }

        LocalDateTime now =
                currentDatabaseTime();

        if (action.getStatus()
                == AgentActionStatus.CANCELLED) {
            return ActionOperationResult.success(
                    AgentActionStatusResponse.from(
                            action
                    )
            );
        }

        if (action.getStatus()
                == AgentActionStatus.CONFIRMED) {
            return ActionOperationResult.failure(
                    40903,
                    "已经确认的动作不能取消"
            );
        }

        if (action.getStatus()
                == AgentActionStatus.EXPIRED) {
            return ActionOperationResult.failure(
                    40903,
                    "已经过期的动作不能取消"
            );
        }

        if (action.getStatus()
                != AgentActionStatus.PENDING) {
            return ActionOperationResult.failure(
                    40903,
                    "动作状态不允许取消"
            );
        }

        /*
         * 到达有效期后，即使数据库仍写着 PENDING，
         * 也必须先迁移为 EXPIRED。
         */
        if (action.getExpiresAt() == null
                || !action.getExpiresAt()
                .isAfter(now)) {
            action.setStatus(
                    AgentActionStatus.EXPIRED
            );
            action.setUpdatedAt(now);

            updateActionOrThrow(action);

            return ActionOperationResult.failure(
                    40903,
                    "动作已经过期"
            );
        }

        action.setStatus(
                AgentActionStatus.CANCELLED
        );
        action.setUpdatedAt(now);

        updateActionOrThrow(action);

        return ActionOperationResult.success(
                AgentActionStatusResponse.from(
                        action
                )
        );
    }

    /**
     * 校验当前用户和动作 ID。
     */
    private void validateUserAndActionId(
            Long userId,
            Long actionId
    ) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(
                    40001,
                    "用户 ID 不合法"
            );
        }

        if (actionId == null || actionId <= 0) {
            throw new BusinessException(
                    40001,
                    "动作 ID 不合法"
            );
        }
    }

    /**
     * 校验并标准化 Idempotency-Key。
     */
    private String normalizeIdempotencyKey(
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
                || !IDEMPOTENCY_KEY_PATTERN
                .matcher(normalized)
                .matches()) {
            throw new BusinessException(
                    40001,
                    "Idempotency-Key 格式不合法"
            );
        }

        return normalized;
    }

    /**
     * 校验动作存在、归属和类型。
     *
     * <p>返回 null 表示校验通过；返回失败结果表示调用方应停止执行。</p>
     */
    private <T> ActionOperationResult<T>
    validateActionAccess(
            AgentAction action,
            Long userId
    ) {
        if (action == null) {
            return ActionOperationResult.failure(
                    40401,
                    "Agent 动作不存在"
            );
        }

        /*
         * userId 只来自 JWT。
         * 不能仅凭 actionId 就允许确认或取消。
         */
        if (!Objects.equals(
                action.getUserId(),
                userId
        )) {
            return ActionOperationResult.failure(
                    40301,
                    "无权操作该 Agent 动作"
            );
        }

        if (action.getActionType()
                != AgentActionType.ADD_CART_ITEM) {
            return ActionOperationResult.failure(
                    40903,
                    "不支持的 Agent 动作类型"
            );
        }

        if (action.getStatus() == null) {
            throw new BusinessException(
                    50000,
                    "Agent 动作状态异常"
            );
        }

        return null;
    }

    /**
     * 统一取得与 DATETIME(3) 一致的当前时间。
     */
    private LocalDateTime currentDatabaseTime() {
        return LocalDateTime.now()
                .truncatedTo(
                        ChronoUnit.MILLIS
                );
    }

    /**
     * 更新已被 FOR UPDATE 锁定的动作。
     */
    private void updateActionOrThrow(
            AgentAction action
    ) {
        if (actionMapper.updateById(action) != 1) {
            throw new IllegalStateException(
                    "Agent 动作状态更新失败"
            );
        }
    }

    /**
     * 从数据库动作载荷中恢复 SKU 和数量。
     */
    private AgentAddCartActionPayload
    deserializeActionPayload(
            String payloadJson
    ) {
        if (payloadJson == null
                || payloadJson.isBlank()) {
            throw new BusinessException(
                    50000,
                    "Agent 动作载荷异常"
            );
        }

        try {
            return objectMapper.readValue(
                    payloadJson,
                    AgentAddCartActionPayload.class
            );
        } catch (JsonProcessingException exception) {
            /*
             * 不返回或记录完整 payloadJson。
             */
            throw new BusinessException(
                    50000,
                    "Agent 动作载荷异常"
            );
        }
    }

    /**
     * 保存首次确认的结果，用于幂等重放。
     */
    private String serializeConfirmedResult(
            AgentActionConfirmResponse response
    ) {
        try {
            return objectMapper
                    .writeValueAsString(response);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    50000,
                    "保存 Agent 动作结果失败"
            );
        }
    }

    /**
     * 相同幂等键重复确认时恢复第一次的结果。
     */
    private AgentActionConfirmResponse
    deserializeConfirmedResult(
            String resultJson
    ) {
        if (resultJson == null
                || resultJson.isBlank()) {
            throw new BusinessException(
                    50000,
                    "Agent 动作结果异常"
            );
        }

        try {
            return objectMapper.readValue(
                    resultJson,
                    AgentActionConfirmResponse.class
            );
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    50000,
                    "Agent 动作结果异常"
            );
        }
    }

    /**
     * 事务内部操作结果。
     *
     * <p>失败结果在事务内部不抛异常，使 EXPIRED 等状态可以提交；
     * 事务结束后再转换为 BusinessException。</p>
     */
    private record ActionOperationResult<T>(
            T value,
            Integer errorCode,
            String errorMessage
    ) {
        private static <T>
        ActionOperationResult<T> success(
                T value
        ) {
            return new ActionOperationResult<>(
                    Objects.requireNonNull(value),
                    null,
                    null
            );
        }

        private static <T>
        ActionOperationResult<T> failure(
                int errorCode,
                String errorMessage
        ) {
            return new ActionOperationResult<>(
                    null,
                    errorCode,
                    errorMessage
            );
        }

        private boolean failed() {
            return errorCode != null;
        }
    }

    /**
     * 在事务完成后将内部失败结果转换成接口业务异常。
     */
    private <T> T unwrapResult(
            ActionOperationResult<T> result
    ) {
        if (result == null) {
            throw new BusinessException(
                    50000,
                    "Agent 动作处理失败"
            );
        }

        if (result.failed()) {
            throw new BusinessException(
                    result.errorCode(),
                    result.errorMessage()
            );
        }

        return result.value();
    }
}
