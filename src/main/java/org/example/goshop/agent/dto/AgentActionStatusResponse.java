package org.example.goshop.agent.dto;

import org.example.goshop.agent.entity.AgentAction;

import java.time.Instant;
import java.time.ZoneId;

/**
 * 取消动作等状态操作的响应。
 *
 * <p>该 DTO 不返回 payloadJson、idempotencyKey 或 resultJson，
 * 防止把内部动作载荷直接暴露给前端。</p>
 */
public record AgentActionStatusResponse(
        Long actionId,
        String actionType,
        String status,
        Instant expiresAt,
        Instant executedAt
) {
    public AgentActionStatusResponse {
        if (actionId == null || actionId <= 0) {
            throw new IllegalArgumentException(
                    "动作 ID 必须为正数"
            );
        }

        if (!"ADD_CART_ITEM".equals(actionType)) {
            throw new IllegalArgumentException(
                    "动作类型不合法"
            );
        }

        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException(
                    "动作状态不能为空"
            );
        }

        if (expiresAt == null) {
            throw new IllegalArgumentException(
                    "动作过期时间不能为空"
            );
        }
    }

    /**
     * 从实体构建不包含内部 JSON 和幂等键的状态响应。
     */
    public static AgentActionStatusResponse from(
            AgentAction action
    ) {
        if (action == null
                || action.getActionType() == null
                || action.getStatus() == null
                || action.getExpiresAt() == null) {
            throw new IllegalArgumentException(
                    "Agent 动作数据不完整"
            );
        }

        ZoneId zoneId =
                ZoneId.systemDefault();

        return new AgentActionStatusResponse(
                action.getId(),
                action.getActionType().name(),
                action.getStatus().name(),
                action.getExpiresAt()
                        .atZone(zoneId)
                        .toInstant(),
                action.getExecutedAt() == null
                        ? null
                        : action.getExecutedAt()
                        .atZone(zoneId)
                        .toInstant()
        );
    }
}
