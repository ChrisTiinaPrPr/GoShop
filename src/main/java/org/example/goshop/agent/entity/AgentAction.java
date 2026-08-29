package org.example.goshop.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 创建且必须由买家确认的业务动作。
 *
 * <p>首期只支持 ADD_CART_ITEM。模型调用 propose_add_cart_item 后，
 * 后端创建一条 PENDING 动作，但此时不会修改购物车。</p>
 *
 * <p>前端展示确认卡片，用户点击确认后，后端根据 actionId
 * 重新读取服务端保存的 payloadJson，再调用 CartService。</p>
 *
 * <p>确认请求不能重新提交 skuId 和 quantity，否则用户或攻击者
 * 可以篡改模型原本展示的操作内容。</p>
 */
@Data
@TableName("agent_action")
public class AgentAction {

    /** 动作主键，也是前端确认接口使用的 actionId。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 动作来源的 Agent 会话。 */
    private Long conversationId;

    /**
     * 动作所属买家，对应 sys_user.id。
     *
     * <p>确认和取消时必须同时校验 id 和 userId，
     * 防止其他用户猜测 actionId 后操作该动作。</p>
     */
    private Long userId;

    /** 动作类型，首期只能是 ADD_CART_ITEM。 */
    private AgentActionType actionType;

    /**
     * 服务端生成的动作载荷 JSON。
     *
     * <p>建议保存：</p>
     *
     * <pre>
     * {
     *   "skuId": 10001,
     *   "quantity": 1,
     *   "productTitle": "办公机械键盘",
     *   "specsJson": "...",
     *   "priceCent": 29900
     * }
     * </pre>
     *
     * <p>真正确认时仍然要由 CartService 重新校验 SKU 是否启用、
     * 最新库存和最新价格；这里的标题和价格只是确认卡片快照。</p>
     */
    private String payloadJson;

    /** 当前动作状态。 */
    private AgentActionStatus status;

    /**
     * 用户确认动作时提交的幂等键。
     *
     * <p>创建 PENDING 动作时为空。第一次确认时写入，
     * 数据库唯一约束为 userId + idempotencyKey。</p>
     */
    private String idempotencyKey;

    /**
     * 动作确认成功后的结果摘要。
     *
     * <p>可以保存 CartItemResponse 的必要字段，用于重复确认时
     * 返回第一次执行结果，不能保存无关用户隐私。</p>
     */
    private String resultJson;

    /** 动作过期时间，默认创建时间后 10 分钟。 */
    private LocalDateTime expiresAt;

    /**
     * 动作确认成功的时间。
     *
     * <p>只有 CONFIRMED 状态可以有值。</p>
     */
    private LocalDateTime executedAt;

    /** 动作创建时间。 */
    private LocalDateTime createdAt;

    /** 动作最后更新时间。 */
    private LocalDateTime updatedAt;
}
