package org.example.goshop.agent.service.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 保存在 agent_action.payload_json 中的加购动作载荷。
 *
 * <p>该对象完全由服务端根据实时商品查询结果创建，
 * 不能直接使用模型传入的商品标题、价格或规格。</p>
 *
 * <p>确认动作时：</p>
 *
 * <ul>
 *     <li>只信任其中的 skuId 和 quantity 作为原始动作内容；</li>
 *     <li>仍然调用 CartService 重新校验商品、SKU、库存和价格；</li>
 *     <li>productTitle、specifications、unitPriceCent 只是确认卡片快照。</li>
 * </ul>
 */
public record AgentAddCartActionPayload(
        Long productId,
        Long skuId,
        Integer quantity,
        String productTitle,
        Map<String, Object> specifications,
        Long unitPriceCent,
        String imageUrl
) {
    /**
     * Agent 单次建议加购的数量上限。
     *
     * <p>这是 Agent 安全上限，不影响用户在普通购物车页面的能力。</p>
     */
    private static final int MAX_ACTION_QUANTITY = 99;

    public AgentAddCartActionPayload {
        if (productId == null || productId <= 0) {
            throw new IllegalArgumentException(
                    "加购动作商品 ID 必须为正数"
            );
        }

        if (skuId == null || skuId <= 0) {
            throw new IllegalArgumentException(
                    "加购动作 SKU ID 必须为正数"
            );
        }

        if (quantity == null
                || quantity <= 0
                || quantity > MAX_ACTION_QUANTITY) {
            throw new IllegalArgumentException(
                    "Agent 加购数量必须在 1～99 之间"
            );
        }

        if (productTitle == null
                || productTitle.isBlank()
                || productTitle.length() > 200) {
            throw new IllegalArgumentException(
                    "加购动作商品标题不合法"
            );
        }

        if (unitPriceCent == null
                || unitPriceCent < 0) {
            throw new IllegalArgumentException(
                    "加购动作商品价格不能为负数"
            );
        }

        if (imageUrl != null
                && imageUrl.length() > 1000) {
            throw new IllegalArgumentException(
                    "加购动作商品图片地址过长"
            );
        }

        /*
         * 不使用 Map.copyOf，因为商品规格中可能存在 null 值。
         *
         * 该 Map 是商品快照，不允许动作创建后被外部修改。
         */
        specifications =
                specifications == null
                        ? Map.of()
                        : Collections.unmodifiableMap(
                        new LinkedHashMap<>(
                                specifications
                        )
                );
    }
}
