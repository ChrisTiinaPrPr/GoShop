package org.example.goshop.agent.tool.cart;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.goshop.agent.entity.AgentToolCall;
import org.example.goshop.agent.mapper.AgentToolCallMapper;
import org.example.goshop.agent.dto.AgentSseData;
import org.example.goshop.agent.service.AgentActionService;
import org.example.goshop.agent.service.AgentToolAuditService;
import org.example.goshop.agent.service.model.AgentAddCartActionProposal;
import org.example.goshop.agent.service.model.AgentToolCallHandle;
import org.example.goshop.agent.tool.AgentToolRequestContext;
import org.example.goshop.common.exception.BusinessException;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 创建“加入购物车”待确认动作的模型工具。
 *
 * <p>该工具只执行以下操作：</p>
 *
 * <ol>
 *     <li>校验商品、SKU、数量和实时库存；</li>
 *     <li>向 agent_action 插入一条 PENDING 记录；</li>
 *     <li>向前端发送 ACTION_REQUIRED 确认卡片。</li>
 * </ol>
 *
 * <p>该工具绝对不能直接调用 CartService，也不能直接修改购物车。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "goshop.agent",
        name = "enabled",
        havingValue = "true"
)
public class ProposeAddCartItemTool {

    private static final String TOOL_NAME =
            "propose_add_cart_item";

    private static final String PRODUCT_DETAIL_TOOL_NAME =
            "get_product_detail";

    private static final String STARTED_TEXT =
            "正在准备加购确认信息";

    private static final String SUCCEEDED_TEXT =
            "加购确认信息已生成";

    private static final String FAILED_TEXT =
            "加购确认信息生成失败";

    private static final String FAILURE_CODE =
            "ADD_CART_PROPOSAL_FAILED";

    private static final int MAX_QUANTITY = 99;

    private static final int MAX_SKU_NAME_CHARS = 500;

    private final AgentActionService
            actionService;

    private final AgentToolAuditService
            toolAuditService;

    private final AgentToolCallMapper toolCallMapper;

    private final ObjectMapper objectMapper;

    /**
     * 为明确的商品 SKU 创建待确认加购动作。
     */
    @Tool(
            name = TOOL_NAME,
            description = """
                    为当前已登录买家创建一个“加入购物车”的待确认动作，
                    并向前端展示确认卡片。

                    只有用户明确要求把某个商品加入购物车时才能调用。
                    普通商品咨询、推荐、比较、价格查询或库存查询不能调用。

                    调用前必须在本次用户消息触发的当前 AgentRun 中，
                    先成功调用 get_product_detail，获得真实的 productId、
                    skuId、价格、规格和库存。

                    上一轮对话中的助手文字、商品名称和历史推荐不能证明
                    本轮 SKU 已经验证。如果用户说“加入购物车”“就要这个”，
                    应从历史文字提取商品名称，重新调用 search_products，
                    再调用 get_product_detail，然后才能调用本工具。

                    严禁使用 1、0、数组序号、示例值或占位值作为
                    productId 和 skuId。

                    如果用户没有选择具体 SKU，应先查询商品详情并询问用户，
                    不得猜测颜色、尺寸或其他规格。

                    如果用户明确指定“最低价规格”等可唯一确定的条件，
                    可以使用商品详情结果中满足条件的 SKU。

                    此工具只创建 PENDING 待确认动作，不会修改购物车。
                    工具返回后必须告诉用户需要点击确认卡片，
                    不得声称商品已经加入购物车。

                    productId、skuId 和 quantity 是模型唯一可以填写的参数。
                    userId、conversationId 和 runId 由服务端注入，
                    不得要求用户提供用户 ID、JWT 或访问令牌。
                    """
    )
    public AgentAddCartActionProposal
    proposeAddCartItem(
            @ToolParam(
                    description = """
                            商品 ID，必须逐字来自当前 AgentRun 中
                            search_products 或 get_product_detail
                            刚刚返回的真实 productId。
                            不能使用历史记忆、1、0、序号或占位值。
                            """,
                    required = true
            )
            Long productId,

            @ToolParam(
                    description = """
                            SKU ID，必须逐字来自当前 AgentRun 中
                            get_product_detail 刚刚返回的真实 skuId，
                            并且必须属于 productId 对应的商品。
                            不能使用历史记忆、1、0、序号或占位值。
                            """,
                    required = true
            )
            Long skuId,

            @ToolParam(
                    description = """
                            加购数量，必须是 1 到 99 的整数。
                            用户没有明确数量时使用 1。
                            """,
                    required = true
            )
            Integer quantity,

            /*
             * ToolContext 由服务端注入，不会进入模型参数 Schema。
             */
            ToolContext toolContext
    ) {
        AgentToolRequestContext requestContext = null;
        AgentToolCallHandle handle = null;
        boolean successAuditCompleted = false;

        try {
            /*
             * userId、conversationId、runId 只能从可信上下文中取得。
             */
            requestContext =
                    AgentToolRequestContext.from(
                            toolContext
                    );

            /*
             * 即使模型产生了 null 参数，也尽量创建失败审计。
             * buildArgumentsSummary() 不会向 Map 中放入 null。
             */
            handle = toolAuditService.start(
                    requestContext,
                    TOOL_NAME,
                    buildArgumentsSummary(
                            productId,
                            skuId,
                            quantity
                    )
            );

            requestContext.eventChannel()
                    .publishStarted(
                            handle.toolCallId(),
                            handle.toolName(),
                            STARTED_TEXT
                    );

            /*
             * 工具层再次校验模型生成的参数。
             */
            validateArguments(
                    productId,
                    skuId,
                    quantity
            );

            /*
             * 工具描述和系统提示词只能约束模型，不能构成服务端安全边界。
             * 因此在创建 PENDING 动作前，必须核对当前 Run 的详情工具审计：
             * productId 必须完全一致，skuId 必须出现在该详情结果的 skuIds 中。
             */
            validateCurrentRunProductDetail(
                    requestContext.runId(),
                    productId,
                    skuId
            );

            /*
             * 这里只创建 PENDING 动作。
             *
             * AgentActionService 会：
             * 1. 重新查询实时公开商品；
             * 2. 验证 SKU 属于对应商品；
             * 3. 检查当前可用库存；
             * 4. 使用服务端商品数据生成快照；
             * 5. 插入 agent_action。
             *
             * 这个调用不会修改 Redis 购物车。
             */
            AgentAddCartActionProposal proposal =
                    actionService
                            .createPendingAddCartAction(
                                    requestContext.userId(),
                                    requestContext
                                            .conversationId(),
                                    productId,
                                    skuId,
                                    quantity
                            );

            /*
             * ACTION_REQUIRED 必须根据已经保存的服务端 proposal 创建。
             * 不能直接使用模型生成商品标题、价格或规格。
             */
            requestContext.eventChannel()
                    .publishActionRequired(
                            toActionRequiredData(
                                    proposal
                            )
                    );

            /*
             * 工具审计只保存动作 ID 和数值状态，
             * 不保存商品标题、规格、图片或完整动作载荷。
             */
            toolAuditService.completeSucceeded(
                    handle,
                    Map.of(
                            "actionId",
                            proposal.actionId(),
                            "actionType",
                            proposal.actionType(),
                            "status",
                            proposal.status(),
                            "productId",
                            proposal.productId(),
                            "skuId",
                            proposal.skuId(),
                            "quantity",
                            proposal.quantity()
                    )
            );

            successAuditCompleted = true;

            requestContext.eventChannel()
                    .publishCompleted(
                            handle.toolCallId(),
                            handle.toolName(),
                            true,
                            SUCCEEDED_TEXT
                    );

            return proposal;
        } catch (RuntimeException exception) {
            if (handle != null
                    && !successAuditCompleted) {
                safelyCompleteFailed(handle);
                safelyPublishFailed(
                        requestContext,
                        handle
                );
            }

            /*
             * 不记录商品标题、规格、完整动作载荷或用户 ID。
             */
            log.warn(
                    "Agent 加购提案工具执行失败，runId={}, failureType={}",
                    requestContext == null
                            ? null
                            : requestContext.runId(),
                    exception.getClass()
                            .getSimpleName()
            );

            /*
             * 商品、SKU 或库存校验失败通常可以通过重新查询实时商品详情恢复。
             *
             * 这里只向模型返回固定的安全指引，不返回数据库、商品内部状态
             * 或原始异常正文。模型可以据此在同一个 AgentRun 中重新调用
             * search_products 和 get_product_detail，然后重试一次提案工具。
             */
            if (exception instanceof
                    BusinessException businessException
                    && (
                    businessException.getCode() == 40001
                            || businessException.getCode() == 40401
                            || businessException.getCode() == 40901
            )) {
                throw new BusinessException(
                        40901,
                        """
                        商品或 SKU 参数未通过本轮实时校验。
                        请先重新调用 search_products 和 get_product_detail，
                        使用本轮工具返回的真实 productId、skuId 和库存后，
                        再创建一次加购待确认动作。
                        """.strip()
                );
            }

            /*
             * 此时最多创建了待确认动作，购物车没有发生写入。
             */
            throw new BusinessException(
                    50301,
                    "加购确认信息暂时无法生成"
            );
        }
    }

    /**
     * 构建不包含 userId 的审计参数摘要。
     */
    private Map<String, Object> buildArgumentsSummary(
            Long productId,
            Long skuId,
            Integer quantity
    ) {
        Map<String, Object> summary =
                new LinkedHashMap<>();

        summary.put(
                "productIdProvided",
                productId != null
        );

        if (productId != null) {
            summary.put(
                    "productId",
                    productId
            );
        }

        summary.put(
                "skuIdProvided",
                skuId != null
        );

        if (skuId != null) {
            summary.put(
                    "skuId",
                    skuId
            );
        }

        summary.put(
                "quantityProvided",
                quantity != null
        );

        if (quantity != null) {
            summary.put(
                    "quantity",
                    quantity
            );
        }

        return summary;
    }

    /**
     * 校验模型可以填写的三个业务参数。
     */
    private void validateArguments(
            Long productId,
            Long skuId,
            Integer quantity
    ) {
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
                || quantity > MAX_QUANTITY) {
            throw new IllegalArgumentException(
                    "加购数量必须在 1～99 之间"
            );
        }
    }

    /**
     * 验证加购参数来自当前 AgentRun 的成功商品详情结果。
     *
     * <p>这里只读取商品详情工具保存的脱敏摘要，不读取完整工具载荷。
     * 摘要中已经包含 productId 和公开可售 skuIds，足以阻止模型把历史 ID、
     * “第一个商品”对应的数字 1 或其他占位值提交给动作 Service。</p>
     */
    private void validateCurrentRunProductDetail(
            Long runId,
            Long productId,
            Long skuId
    ) {
        AgentToolCall detailCall =
                toolCallMapper.selectLatestSuccessfulInRun(
                        runId,
                        PRODUCT_DETAIL_TOOL_NAME
                );

        if (detailCall == null
                || detailCall.getResultSummaryJson() == null
                || detailCall.getResultSummaryJson().isBlank()) {
            throw new BusinessException(
                    40901,
                    "当前运行尚未成功验证商品详情"
            );
        }

        try {
            JsonNode summary = objectMapper.readTree(
                    detailCall.getResultSummaryJson()
            );

            JsonNode verifiedProductId =
                    summary.path("productId");
            JsonNode verifiedSkuIds =
                    summary.path("skuIds");

            boolean productMatches =
                    verifiedProductId.canConvertToLong()
                            && verifiedProductId.longValue()
                            == productId;

            boolean skuMatches = false;

            if (verifiedSkuIds.isArray()) {
                for (JsonNode verifiedSkuId : verifiedSkuIds) {
                    if (verifiedSkuId.canConvertToLong()
                            && verifiedSkuId.longValue()
                            == skuId) {
                        skuMatches = true;
                        break;
                    }
                }
            }

            if (!productMatches || !skuMatches) {
                throw new BusinessException(
                        40901,
                        "商品或 SKU 未通过当前运行详情验证"
                );
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            /*
             * 审计摘要损坏属于服务端数据异常，不能退化为相信模型参数。
             * 对外仍使用稳定业务错误，不泄露 JSON 或数据库内部细节。
             */
            throw new BusinessException(
                    40901,
                    "当前运行的商品详情验证信息不可用"
            );
        }
    }

    /**
     * 将持久化后的服务端动作快照转换成 SSE 确认卡片。
     */
    private AgentSseData.ActionRequiredData
    toActionRequiredData(
            AgentAddCartActionProposal proposal
    ) {
        /*
         * 数据库存储金额单位为“分”，SSE 契约中的 unitPrice 使用“元”。
         *
         * BigDecimal.valueOf(value, 2) 会把 29900 分准确转换为 299.00 元，
         * 不会引入 double 浮点误差。
         */
        BigDecimal unitPrice =
                BigDecimal.valueOf(
                        proposal.unitPriceCent(),
                        2
                );

        return new AgentSseData
                .ActionRequiredData(
                proposal.actionId(),
                proposal.actionType(),

                /*
                 * 卡片标题使用固定服务端文案，
                 * 不允许由模型或商品描述控制。
                 */
                "确认加入购物车",

                /*
                 * 商品标题来自服务端商品快照。
                 * 前端仍然必须以纯文本方式展示。
                 */
                proposal.productTitle(),

                proposal.productId(),
                proposal.skuId(),
                buildSkuName(
                        proposal.specifications()
                ),
                proposal.quantity(),
                unitPrice,
                proposal.imageUrl(),
                proposal.expiresAt()
        );
    }

    /**
     * 将结构化规格转换为确认卡片中的简短文本。
     *
     * <p>规格来自商家数据，属于不可信文本；前端必须转义展示。</p>
     */
    private String buildSkuName(
            Map<String, Object> specifications
    ) {
        if (specifications == null
                || specifications.isEmpty()) {
            return "默认规格";
        }

        String skuName =
                specifications.entrySet()
                        .stream()
                        /*
                         * 防止异常规格对象包含大量字段。
                         */
                        .limit(10)
                        .map(entry ->
                                normalizeText(
                                        entry.getKey()
                                )
                                        + "："
                                        + normalizeText(
                                        entry.getValue()
                                )
                        )
                        .collect(
                                Collectors.joining(" / ")
                        );

        if (skuName.isBlank()) {
            return "默认规格";
        }

        return skuName.length()
                <= MAX_SKU_NAME_CHARS
                ? skuName
                : skuName.substring(
                0,
                MAX_SKU_NAME_CHARS
        );
    }

    /**
     * 把规格键和值转换为单行纯文本。
     */
    private String normalizeText(Object value) {
        if (value == null) {
            return "";
        }

        /*
         * replaceAll("\\s+", " ") 把换行、制表符和连续空格
         * 合并成单个空格，防止破坏确认卡片布局。
         */
        return String.valueOf(value)
                .replaceAll("\\s+", " ")
                .strip();
    }

    private void safelyCompleteFailed(
            AgentToolCallHandle handle
    ) {
        try {
            toolAuditService.completeFailed(
                    handle,
                    FAILURE_CODE
            );
        } catch (RuntimeException ignored) {
            log.warn(
                    "Agent 加购提案失败审计收口失败，runId={}, auditId={}",
                    handle.runId(),
                    handle.auditId()
            );
        }
    }

    private void safelyPublishFailed(
            AgentToolRequestContext requestContext,
            AgentToolCallHandle handle
    ) {
        if (requestContext == null) {
            return;
        }

        try {
            requestContext.eventChannel()
                    .publishCompleted(
                            handle.toolCallId(),
                            handle.toolName(),
                            false,
                            FAILED_TEXT
                    );
        } catch (RuntimeException ignored) {
            log.warn(
                    "Agent 加购提案失败事件发布失败，runId={}, toolCallId={}",
                    handle.runId(),
                    handle.toolCallId()
            );
        }
    }
}
