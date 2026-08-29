package org.example.goshop.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.goshop.agent.dto.AgentMessageResponse;
import org.example.goshop.agent.dto.AgentResultCardData;
import org.example.goshop.agent.entity.AgentMessage;
import org.example.goshop.agent.mapper.AgentMessageMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * Agent 结构化结果卡片的持久化与安全反序列化服务。
 *
 * <p>卡片与助手可见消息保存在同一事实表中，因此刷新页面、断流后重新查询
 * 历史以及相同 clientMessageId 的结果重放都能恢复相同卡片。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentResultCardPersistenceService {

    /** 防止意外把过大的工具结果复制进消息表。 */
    private static final int MAX_CARD_JSON_CHARS = 50_000;

    private static final TypeReference<List<AgentResultCardData>> CARD_LIST_TYPE =
            new TypeReference<>() {
            };

    private final AgentMessageMapper messageMapper;
    private final ObjectMapper objectMapper;

    /**
     * 绑定工具调用 ID 并在独立短事务中追加卡片。
     *
     * <p>模型流本身不持有数据库事务；REQUIRES_NEW 确保卡片在 SSE 发出前
     * 已经落库。若更新条件不满足，说明运行已结束或消息关联异常，不能继续
     * 向前端发送一张无法从历史恢复的“幽灵卡片”。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AgentResultCardData append(
            Long runId,
            String toolCallId,
            AgentResultCardData card
    ) {
        Objects.requireNonNull(runId, "Agent 运行 ID 不能为空");
        Objects.requireNonNull(card, "Agent 结果卡片不能为空");

        AgentResultCardData boundCard =
                card.bindToolCallId(toolCallId);
        String cardJson = serialize(boundCard);

        if (messageMapper.appendResultCard(runId, cardJson) != 1) {
            throw new IllegalStateException("保存 Agent 结果卡片失败");
        }

        return boundCard;
    }

    /** 读取消息中的卡片；旧数据或空字段统一返回不可变空列表。 */
    public List<AgentResultCardData> read(AgentMessage message) {
        if (message == null
                || message.getResultCardsJson() == null
                || message.getResultCardsJson().isBlank()) {
            return List.of();
        }

        try {
            List<AgentResultCardData> cards = objectMapper.readValue(
                    message.getResultCardsJson(),
                    CARD_LIST_TYPE
            );
            return cards == null ? List.of() : List.copyOf(cards);
        } catch (JsonProcessingException corruptedJson) {
            /*
             * 卡片损坏不能阻断纯文本历史。日志不输出 JSON、商品标题、
             * 订单号或异常正文，避免业务数据进入普通日志。
             */
            log.warn("Agent 消息结果卡片 JSON 无法解析，降级为纯文本历史");
            return List.of();
        }
    }

    /** 统一组装包含持久化卡片的消息响应。 */
    public AgentMessageResponse toResponse(AgentMessage message) {
        return AgentMessageResponse.from(message, read(message));
    }

    private String serialize(AgentResultCardData card) {
        try {
            String json = objectMapper.writeValueAsString(card);
            if (json.length() > MAX_CARD_JSON_CHARS) {
                throw new IllegalStateException("Agent 结果卡片数据过大");
            }
            return json;
        } catch (JsonProcessingException serializationFailure) {
            throw new IllegalStateException("序列化 Agent 结果卡片失败");
        }
    }
}
