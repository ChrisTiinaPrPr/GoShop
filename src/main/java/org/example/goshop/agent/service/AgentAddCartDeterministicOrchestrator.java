package org.example.goshop.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.goshop.agent.entity.AgentToolCall;
import org.example.goshop.agent.mapper.AgentToolCallMapper;
import org.example.goshop.agent.service.model.AgentAddCartActionProposal;
import org.example.goshop.agent.tool.AgentToolRequestContext;
import org.example.goshop.agent.tool.cart.ProposeAddCartItemTool;
import org.example.goshop.agent.tool.product.AgentProductDetailResult;
import org.example.goshop.agent.tool.product.AgentProductSearchItem;
import org.example.goshop.agent.tool.product.AgentProductSearchResult;
import org.example.goshop.agent.tool.product.AgentProductSkuDetail;
import org.example.goshop.agent.tool.product.AgentProductQueryService;
import org.example.goshop.agent.tool.product.ProductDetailTool;
import org.example.goshop.agent.tool.product.ProductSearchTool;
import org.example.goshop.product.dto.ProductSort;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 明确加购请求的服务端确定性编排器。
 *
 * <p>模型擅长理解和推荐，但不能作为“动作是否真的创建”的事实来源。
 * 对“把黑色的加入购物车”这类已经明确表达写意图的消息，本类直接复用
 * 现有受控 Java 工具，固定执行：</p>
 *
 * <pre>
 * search_products
 *     -> get_product_detail
 *     -> propose_add_cart_item
 * </pre>
 *
 * <p>三个工具仍会正常写入 agent_tool_call、发布 SSE 事件，并由
 * propose_add_cart_item 创建 PENDING agent_action。这里不会绕过工具审计，
 * 也不会直接调用 CartService 修改购物车。</p>
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "goshop.agent",
        name = "enabled",
        havingValue = "true"
)
public class AgentAddCartDeterministicOrchestrator {

    private static final String PRODUCT_DETAIL_TOOL =
            "get_product_detail";

    private static final int SEARCH_LIMIT = 10;

    private static final Pattern EXPLICIT_QUANTITY =
            Pattern.compile(
                    "(?:数量\\s*)?(\\d{1,2})\\s*(?:件|个|份)"
                            + "|数量\\s*(\\d{1,2})"
            );

    /**
     * 从上一轮“推荐/购买”请求中提取商品搜索词。
     *
     * <p>这里故意只提取用户亲自说出的商品描述，不从模型回复中解析 ID。
     * 例如“有没有推荐的耳机”得到“耳机”，“我想买一款红轴机械键盘”得到
     * “红轴机械键盘”。这样第二次加购时，即使上一轮模型错误地只输出文字、
     * 没有调用商品工具，本轮仍会用“耳机”重新搜索，而不会复用第一次加购的
     * 键盘 productId。</p>
     */
    private static final List<Pattern> SEARCH_TARGET_PATTERNS =
            List.of(
                    Pattern.compile(
                            "(?:推荐|介绍|搜索|搜|找|看看)"
                                    + "(?:给我|一下|一款|一个|一些|几款|的)*"
                                    + "([^，。！？!?；;]{2,30})"
                    ),
                    Pattern.compile(
                            "(?:想买|要买|购买|买)"
                                    + "(?:一款|一个|一些|几款)?"
                                    + "([^，。！？!?；;]{2,30})"
                    )
            );

    private static final Pattern SEARCH_TARGET_TRAILING_NOISE =
            Pattern.compile(
                    "(?:帮我)?(?:推荐|介绍|搜索|搜|找|看看)"
                            + "(?:给我|一下|一款|一个|一些|几款)?$"
            );

    private static final Map<Character, List<String>>
            COLOR_ALIASES = Map.ofEntries(
            Map.entry('黑', List.of("黑", "black")),
            Map.entry('白', List.of("白", "white")),
            Map.entry('红', List.of("红", "red")),
            Map.entry('蓝', List.of("蓝", "blue")),
            Map.entry('绿', List.of("绿", "green")),
            Map.entry('粉', List.of("粉", "pink")),
            Map.entry('紫', List.of("紫", "purple")),
            Map.entry('灰', List.of("灰", "gray", "grey")),
            Map.entry('银', List.of("银", "silver")),
            Map.entry('金', List.of("金", "gold"))
    );

    private final AgentToolCallMapper toolCallMapper;
    private final ObjectMapper objectMapper;
    private final AgentProductQueryService productQueryService;
    private final ProductSearchTool productSearchTool;
    private final ProductDetailTool productDetailTool;
    private final ProposeAddCartItemTool proposeAddCartItemTool;

    /**
     * 执行明确加购请求，并返回可以安全落库的固定助手文案。
     *
     * <p>成功文案只会在 propose_add_cart_item 已返回服务端动作对象后产生。
     * 若没有历史商品或 SKU 无法唯一确定，只返回澄清提示，绝不声称卡片
     * 已创建。</p>
     */
    public String execute(
            String currentUserContent,
            List<Message> modelMessages,
            AgentToolRequestContext requestContext
    ) {
        Objects.requireNonNull(
                requestContext,
                "Agent 工具请求上下文不能为空"
        );

        Long previousProductId =
                findPreviousProductId(requestContext);

        if (previousProductId == null) {
            return "请先告诉我需要加入购物车的具体商品和规格。";
        }

        /*
         * 历史 productId 只能作为搜索词的兜底来源，不能直接传给加购工具。
         * 搜索词优先取上一轮用户明确说出的商品类型。例如第二次推荐请求是
         * “有没有推荐的耳机”时必须搜索“耳机”，不能因为最近一次成功详情仍是
         * 第一次的键盘，就错误地把键盘加入购物车。
         */
        AgentProductDetailResult previousDetail =
                productQueryService.getDetail(
                        previousProductId
                );

        String searchKeyword = resolveSearchKeyword(
                currentUserContent,
                modelMessages,
                previousDetail.title()
        );

        ToolContext toolContext =
                new ToolContext(requestContext.toMap());

        AgentProductSearchResult searchResult =
                productSearchTool.searchProducts(
                        searchKeyword,
                        null,
                        null,
                        null,
                        ProductSort.LATEST,
                        SEARCH_LIMIT,
                        toolContext
                );

        Long currentProductId =
                findCurrentProductId(
                        previousProductId,
                        searchResult,
                        modelMessages
                );

        if (currentProductId == null) {
            return "没有重新搜索到刚才的商品，暂时不能创建加购确认卡片。";
        }

        AgentProductDetailResult currentDetail =
                productDetailTool.getProductDetail(
                        currentProductId,
                        toolContext
                );

        String selectionContext =
                buildSelectionContext(
                        currentUserContent,
                        modelMessages
                );

        AgentProductSkuDetail selectedSku =
                selectUniqueSku(
                        currentDetail.skus(),
                        currentUserContent,
                        selectionContext
                );

        if (selectedSku == null) {
            return "还不能唯一确定要加入购物车的规格，请明确颜色、型号或其他 SKU 选项。";
        }

        int quantity = parseQuantity(currentUserContent);

        AgentAddCartActionProposal proposal =
                proposeAddCartItemTool
                        .proposeAddCartItem(
                                currentDetail.productId(),
                                selectedSku.skuId(),
                                quantity,
                                toolContext
                        );

        /*
         * 不使用模型撰写成功文案。只有真实 proposal 存在时才会走到这里，
         * 因此该句话与 ACTION_REQUIRED 和 PENDING agent_action 一一对应。
         */
        return "已生成加购待确认卡片，请核对商品、规格、数量和价格后点击确认。"
                + "本次待确认数量为 "
                + proposal.quantity()
                + " 件。";
    }

    /**
     * 从最近一次成功详情工具审计中读取 productId。
     */
    private Long findPreviousProductId(
            AgentToolRequestContext requestContext
    ) {
        AgentToolCall call =
                toolCallMapper
                        .selectLatestSuccessfulBeforeRun(
                                requestContext
                                        .conversationId(),
                                requestContext.runId(),
                                PRODUCT_DETAIL_TOOL
                        );

        if (call == null
                || call.getResultSummaryJson() == null
                || call.getResultSummaryJson().isBlank()) {
            return null;
        }

        try {
            JsonNode productIdNode =
                    objectMapper
                            .readTree(
                                    call.getResultSummaryJson()
                            )
                            .path("productId");

            if (!productIdNode.canConvertToLong()) {
                return null;
            }

            long productId = productIdNode.longValue();
            return productId > 0 ? productId : null;
        } catch (Exception exception) {
            /*
             * 审计摘要损坏时不能猜测 ID，也不能把 JSON 异常暴露给用户。
             */
            return null;
        }
    }

    /**
     * 从当前运行的实时搜索结果中确定商品，绝不把历史 productId 直接当结果使用。
     *
     * <p>只有一个结果时可以唯一确定；存在多个结果时，优先匹配上一条助手推荐中
     * 明确出现的完整商品标题。若仍有歧义，仅当历史商品确实也出现在本次搜索结果
     * 中时才回退到它，否则返回 {@code null} 要求用户澄清。</p>
     */
    private Long findCurrentProductId(
            Long previousProductId,
            AgentProductSearchResult searchResult,
            List<Message> modelMessages
    ) {
        if (searchResult == null
                || searchResult.items() == null
                || searchResult.items().isEmpty()) {
            return null;
        }

        List<AgentProductSearchItem> items =
                searchResult.items().stream()
                        .filter(Objects::nonNull)
                        .filter(item -> item.productId() != null)
                        .toList();

        if (items.size() == 1) {
            return items.get(0).productId();
        }

        String assistantContext =
                findLatestAssistantContent(modelMessages);

        List<Long> assistantTitleMatches = items.stream()
                .filter(item -> item.title() != null)
                .filter(item -> assistantContext.contains(
                        normalize(item.title())
                ))
                .map(AgentProductSearchItem::productId)
                .distinct()
                .toList();

        if (assistantTitleMatches.size() == 1) {
            return assistantTitleMatches.get(0);
        }

        return items
                .stream()
                .map(AgentProductSearchItem::productId)
                .filter(previousProductId::equals)
                .findFirst()
                .orElse(null);
    }

    /**
     * 优先从当前加购消息之前的最近一条用户消息提取搜索目标。
     *
     * <p>消息列表在不同调用入口下可能包含、也可能不包含当前用户消息，所以先
     * 跳过最后一条与 currentUserContent 相同的消息。提取失败时才使用最近一次
     * 已成功详情的标题，保证第一次“行，加入购物车”的兼容性。</p>
     */
    private String resolveSearchKeyword(
            String currentUserContent,
            List<Message> modelMessages,
            String fallbackTitle
    ) {
        /*
         * 首次加购时，最近助手回复通常已经明确展示了刚查过的完整商品标题。
         * 此时继续用该标题重新搜索最准确，也能避免“红轴机械键盘”这类规格词
         * 组合因商品标题只包含“机械键盘”而搜索不到。
         *
         * 第二次推荐若模型跳过工具，最近助手回复已经转向耳机，不会再包含旧
         * 键盘标题；只有这种情况下才继续向下提取最近用户说出的新商品类别。
         */
        String latestAssistantContent =
                findLatestAssistantContent(modelMessages);

        if (fallbackTitle != null
                && !fallbackTitle.isBlank()
                && latestAssistantContent.contains(
                        normalize(fallbackTitle)
                )) {
            return fallbackTitle;
        }

        String current = normalize(currentUserContent);
        boolean currentMessageSkipped = false;

        if (modelMessages != null) {
            for (int index = modelMessages.size() - 1;
                 index >= 0;
                 index--) {
                Message message = modelMessages.get(index);

                if (!(message instanceof UserMessage userMessage)) {
                    continue;
                }

                String userText = userMessage.getText();

                if (!currentMessageSkipped
                        && normalize(userText).equals(current)) {
                    currentMessageSkipped = true;
                    continue;
                }

                String target = extractSearchTarget(userText);
                if (!target.isBlank()) {
                    return target;
                }
            }
        }

        return fallbackTitle;
    }

    private String extractSearchTarget(String userText) {
        if (userText == null || userText.isBlank()) {
            return "";
        }

        for (Pattern pattern : SEARCH_TARGET_PATTERNS) {
            Matcher matcher = pattern.matcher(userText.strip());

            if (!matcher.find()) {
                continue;
            }

            String target = SEARCH_TARGET_TRAILING_NOISE
                    .matcher(matcher.group(1).strip())
                    .replaceFirst("")
                    .strip();

            if (target.length() >= 2) {
                return target;
            }
        }

        return "";
    }

    private String findLatestAssistantContent(
            List<Message> modelMessages
    ) {
        if (modelMessages == null) {
            return "";
        }

        for (int index = modelMessages.size() - 1;
             index >= 0;
             index--) {
            Message message = modelMessages.get(index);

            if (message instanceof AssistantMessage assistant) {
                return normalize(assistant.getText());
            }
        }

        return "";
    }

    /**
     * 合并当前用户选择和最近一条成功助手推荐，辅助解析“这个”“黑色的”。
     */
    private String buildSelectionContext(
            String currentUserContent,
            List<Message> modelMessages
    ) {
        StringBuilder context = new StringBuilder();

        if (currentUserContent != null) {
            context.append(currentUserContent);
        }

        if (modelMessages != null) {
            for (int index = modelMessages.size() - 1;
                 index >= 0;
                 index--) {
                Message message = modelMessages.get(index);

                if (message instanceof AssistantMessage assistant) {
                    context.append(' ')
                            .append(assistant.getText());
                    break;
                }
            }
        }

        return normalize(context.toString());
    }

    /**
     * 从实时详情中唯一选择 SKU。
     *
     * <p>评分只用于选择已经由 get_product_detail 返回的 SKU，不会生成
     * 新 ID。当前用户消息的颜色/规格匹配权重高于历史推荐，防止用户在
     * 后续消息中改变选择。</p>
     */
    private AgentProductSkuDetail selectUniqueSku(
            List<AgentProductSkuDetail> skus,
            String currentUserContent,
            String selectionContext
    ) {
        List<AgentProductSkuDetail> inStockSkus =
                skus == null
                        ? List.of()
                        : skus.stream()
                        .filter(sku ->
                                sku != null
                                        && sku.skuId() != null
                                        && sku.skuId() > 0
                                        && sku.availableStock() != null
                                        && sku.availableStock() > 0
                        )
                        .toList();

        if (inStockSkus.size() == 1) {
            return inStockSkus.get(0);
        }

        String current = normalize(currentUserContent);
        int bestScore = 0;
        List<AgentProductSkuDetail> best =
                new ArrayList<>();

        for (AgentProductSkuDetail sku : inStockSkus) {
            String specification =
                    normalizeSpecifications(
                            sku.specifications()
                    );

            int score = scoreSpecification(
                    specification,
                    current,
                    selectionContext
            );

            if (score > bestScore) {
                bestScore = score;
                best.clear();
                best.add(sku);
            } else if (score == bestScore
                    && score > 0) {
                best.add(sku);
            }
        }

        return bestScore > 0 && best.size() == 1
                ? best.get(0)
                : null;
    }

    private int scoreSpecification(
            String specification,
            String current,
            String selectionContext
    ) {
        int score = 0;

        for (Map.Entry<Character, List<String>> entry :
                COLOR_ALIASES.entrySet()) {
            boolean specificationMatchesColor =
                    entry.getValue()
                            .stream()
                            .anyMatch(specification::contains);

            if (!specificationMatchesColor) {
                continue;
            }

            if (current.indexOf(entry.getKey()) >= 0) {
                score += 100;
            } else if (selectionContext
                    .indexOf(entry.getKey()) >= 0) {
                /*
                 * “行”“可以”没有重复颜色时，可以使用上一条助手明确展示的
                 * 颜色作为低权重选择依据。例如助手已询问“是否把深空黑
                 * （线性红轴）加入购物车”，用户回复“行”，应能唯一命中
                 * 深空黑 SKU。当前用户显式指定的颜色仍以 100 分优先。
                 */
                score += 20;
            }
        }

        /*
         * 对“红轴”“青轴”等常见键盘轴体做精确短语匹配。
         */
        for (String axis : List.of(
                "红轴", "青轴", "茶轴", "黑轴", "银轴"
        )) {
            if (current.contains(axis)
                    && specification.contains(axis)) {
                score += 100;
            } else if (selectionContext.contains(axis)
                    && specification.contains(axis)) {
                score += 20;
            }
        }

        /*
         * 历史推荐中出现完整规格值时只给较低权重，当前轮明确选择优先。
         */
        if (!specification.isBlank()
                && selectionContext.contains(specification)) {
            score += 10;
        }

        return score;
    }

    private String normalizeSpecifications(
            Map<String, Object> specifications
    ) {
        if (specifications == null
                || specifications.isEmpty()) {
            return "";
        }

        return normalize(
                String.join(
                        " ",
                        specifications.values()
                                .stream()
                                .map(String::valueOf)
                                .toList()
                )
        );
    }

    private int parseQuantity(String currentUserContent) {
        if (currentUserContent == null) {
            return 1;
        }

        Matcher matcher =
                EXPLICIT_QUANTITY
                        .matcher(currentUserContent);

        if (matcher.find()) {
            String value = Optional.ofNullable(
                            matcher.group(1)
                    )
                    .orElse(matcher.group(2));

            int quantity = Integer.parseInt(value);

            if (quantity >= 1 && quantity <= 99) {
                return quantity;
            }
        }

        if (currentUserContent.contains("两件")
                || currentUserContent.contains("两个")) {
            return 2;
        }

        return 1;
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "")
                .strip();
    }
}
