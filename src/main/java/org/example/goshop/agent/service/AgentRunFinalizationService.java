package org.example.goshop.agent.service;

import lombok.RequiredArgsConstructor;
import org.example.goshop.agent.dto.AgentMessageResponse;
import org.example.goshop.agent.entity.AgentMessage;
import org.example.goshop.agent.entity.AgentMessageRole;
import org.example.goshop.agent.entity.AgentMessageStatus;
import org.example.goshop.agent.entity.AgentRun;
import org.example.goshop.agent.entity.AgentRunStatus;
import org.example.goshop.agent.mapper.AgentConversationMapper;
import org.example.goshop.agent.mapper.AgentMessageMapper;
import org.example.goshop.agent.mapper.AgentRunMapper;
import org.example.goshop.agent.service.model.AgentRunFinalization;
import org.example.goshop.agent.service.model.AgentRunMetrics;
import org.example.goshop.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AgentRunFinalizationService {

    /**
     * 防止超长模型内容超过 MySQL TEXT 的字节容量。
     *
     * <p>16000 个 Unicode 字符即使大部分使用 utf8mb4，
     * 也可以控制在 TEXT 的容量范围附近。</p>
     */
    private static final int MAX_ASSISTANT_CODE_POINTS = 16_000;

    /**
     * 数据库 error_code 字段为 VARCHAR(50)。
     */
    private static final int MAX_ERROR_CODE_LENGTH = 50;

    /**
     * 页面失败提示不需要保存过长内容。
     */
    private static final int MAX_SAFE_MESSAGE_CODE_POINTS = 500;

    private final AgentRunMapper runMapper;
    private final AgentMessageMapper messageMapper;
    private final AgentConversationMapper conversationMapper;
    private final AgentResultCardPersistenceService resultCardPersistenceService;

    /**
     * 成功完成一次 AgentRun。
     *
     * <p>同一个 runId 重复调用时不会再次覆盖数据，
     * 而是返回数据库当前的终态。</p>
     */
    @Transactional
    public AgentRunFinalization complete(
            Long runId,
            String assistantContent,
            AgentRunMetrics metrics
    ) {
        Objects.requireNonNull(
                metrics,
                "运行指标不能为空"
        );

        String normalizedContent =
                normalizeAssistantContent(
                        assistantContent
                );

        AgentRun run = requireRunForUpdate(runId);
        AgentMessage assistantMessage =
                requireAssistantMessage(run);

        /*
         * 可能因为网络重试、超时任务竞争或 Reactor 重复回调，
         * 当前运行已经被其他事务收口。
         *
         * 此时不能再次更新，只返回已经存在的最终状态。
         */
        if (run.getStatus() != AgentRunStatus.RUNNING) {
            return existingFinalization(
                    run,
                    assistantMessage
            );
        }

        LocalDateTime finishedAt = now();

        requireOneRow(
                messageMapper.completeAssistantMessage(
                        assistantMessage.getId(),
                        run.getConversationId(),
                        run.getId(),
                        normalizedContent,
                        finishedAt
                ),
                "完成 Agent 助手消息失败"
        );

        requireOneRow(
                runMapper.completeRun(
                        run.getId(),
                        metrics.promptTokens(),
                        metrics.completionTokens(),
                        metrics.firstTokenMs(),
                        metrics.durationMs(),
                        finishedAt
                ),
                "完成 Agent 运行记录失败"
        );

        /*
         * 助手消息成功完成后，才把会话最后消息推进到助手消息。
         *
         * advanceLastMessage 本身具有单调条件，返回 0 也可能表示
         * 会话游标已经位于更新的消息，因此这里不强制要求必须为 1。
         */
        conversationMapper.advanceLastMessage(
                run.getConversationId(),
                assistantMessage.getId(),
                finishedAt
        );

        /*
         * 同步更新内存对象，供 SSE MESSAGE_COMPLETED 直接转换 DTO。
         */
        assistantMessage.setContent(normalizedContent);
        assistantMessage.setStatus(
                AgentMessageStatus.COMPLETED
        );
        assistantMessage.setCompletedAt(finishedAt);

        return new AgentRunFinalization(
                AgentRunStatus.COMPLETED,
                resultCardPersistenceService.toResponse(
                        assistantMessage
                ),
                true,
                null
        );
    }

    /**
     * 把一次运行收口为 FAILED。
     */
    @Transactional
    public AgentRunFinalization fail(
            Long runId,
            String errorCode,
            String safeMessage,
            Integer durationMs
    ) {
        return finishWithError(
                runId,
                AgentRunStatus.FAILED,
                errorCode,
                safeMessage,
                durationMs
        );
    }

    /**
     * 把一次运行收口为 TIMED_OUT。
     */
    @Transactional
    public AgentRunFinalization timeout(
            Long runId,
            String safeMessage,
            Integer durationMs
    ) {
        return finishWithError(
                runId,
                AgentRunStatus.TIMED_OUT,
                "MODEL_TIMEOUT",
                safeMessage,
                durationMs
        );
    }

    /**
     * 失败和超时共用的内部状态转换。
     */
    private AgentRunFinalization finishWithError(
            Long runId,
            AgentRunStatus terminalStatus,
            String errorCode,
            String safeMessage,
            Integer durationMs
    ) {
        if (terminalStatus != AgentRunStatus.FAILED
                && terminalStatus != AgentRunStatus.TIMED_OUT) {
            throw new IllegalArgumentException(
                    "错误收口状态只能是 FAILED 或 TIMED_OUT"
            );
        }

        String normalizedErrorCode =
                normalizeErrorCode(errorCode);

        String normalizedSafeMessage =
                normalizeSafeMessage(safeMessage);

        if (durationMs != null && durationMs < 0) {
            throw new IllegalArgumentException(
                    "运行总耗时不能是负数"
            );
        }

        AgentRun run = requireRunForUpdate(runId);
        AgentMessage assistantMessage =
                requireAssistantMessage(run);

        /*
         * 已经成功、失败或超时的运行不允许再次被改写。
         *
         * 例如模型在 45 秒超时后又迟到返回成功内容，
         * timeout 事务已经先把它改为 TIMED_OUT，
         * 后续 complete 调用只能读取终态，不能复活运行。
         */
        if (run.getStatus() != AgentRunStatus.RUNNING) {
            return existingFinalization(
                    run,
                    assistantMessage
            );
        }

        LocalDateTime finishedAt = now();

        requireOneRow(
                messageMapper.failAssistantMessage(
                        assistantMessage.getId(),
                        run.getConversationId(),
                        run.getId(),
                        normalizedSafeMessage,
                        finishedAt
                ),
                "更新 Agent 失败消息失败"
        );

        requireOneRow(
                runMapper.failRun(
                        run.getId(),
                        terminalStatus,
                        durationMs,
                        normalizedErrorCode,
                        finishedAt
                ),
                "更新 Agent 失败运行记录失败"
        );

        /*
         * 失败助手消息仍会出现在历史列表中，
         * 但不会作为有效上下文发送给下一轮模型。
         *
         * 会话 last_message_id 保持在用户消息，
         * 与初始化事务中的约定一致。
         */
        assistantMessage.setContent(
                normalizedSafeMessage
        );
        assistantMessage.setStatus(
                AgentMessageStatus.FAILED
        );
        assistantMessage.setCompletedAt(finishedAt);

        return new AgentRunFinalization(
                terminalStatus,
                resultCardPersistenceService.toResponse(
                        assistantMessage
                ),
                true,
                normalizedErrorCode
        );
    }

    /**
     * 查询并锁定运行记录。
     */
    private AgentRun requireRunForUpdate(Long runId) {
        if (runId == null) {
            throw new IllegalArgumentException(
                    "runId 不能为空"
            );
        }

        AgentRun run =
                runMapper.selectByIdForUpdate(runId);

        if (run == null) {
            /*
             * runId 只能来自服务端刚创建的运行，不由前端指定。
             * 找不到通常表示内部数据异常。
             */
            throw new BusinessException(
                    50000,
                    "Agent 运行记录不存在"
            );
        }

        return run;
    }

    /**
     * 读取并验证运行对应的助手消息。
     */
    private AgentMessage requireAssistantMessage(
            AgentRun run
    ) {
        AgentMessage message =
                messageMapper.selectInConversation(
                        run.getConversationId(),
                        run.getAssistantMessageId()
                );

        if (message == null
                || message.getRole()
                != AgentMessageRole.ASSISTANT
                || !Objects.equals(
                message.getRunId(),
                run.getId()
        )) {
            throw new BusinessException(
                    50000,
                    "Agent 助手消息关联异常"
            );
        }

        return message;
    }

    /**
     * 返回其他事务已经写入的最终状态。
     */
    private AgentRunFinalization existingFinalization(
            AgentRun run,
            AgentMessage assistantMessage
    ) {
        /*
         * 数据库事务保证运行和助手消息一起转换状态。
         * 如果运行已结束但消息仍是 STREAMING，说明存在数据损坏。
         */
        if (assistantMessage.getStatus()
                == AgentMessageStatus.STREAMING) {
            throw new BusinessException(
                    50000,
                    "Agent 运行与消息状态不一致"
            );
        }

        return new AgentRunFinalization(
                run.getStatus(),
                resultCardPersistenceService.toResponse(
                        assistantMessage
                ),
                false,
                run.getErrorCode()
        );
    }

    /**
     * 校验模型最终输出。
     */
    private String normalizeAssistantContent(
            String content
    ) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(
                    50301,
                    "模型未返回有效内容"
            );
        }

        String normalized = content.strip();

        int codePoints = normalized.codePointCount(
                0,
                normalized.length()
        );

        if (codePoints > MAX_ASSISTANT_CODE_POINTS) {
            throw new BusinessException(
                    50301,
                    "模型返回内容过长"
            );
        }

        return normalized;
    }

    /**
     * errorCode 只能保存内部分类，不保存异常正文。
     *
     * <p>合法示例：MODEL_TIMEOUT、MODEL_RATE_LIMIT、TOOL_FAILED。</p>
     */
    private String normalizeErrorCode(
            String errorCode
    ) {
        if (errorCode == null || errorCode.isBlank()) {
            return "UNKNOWN_MODEL_ERROR";
        }

        String normalized = errorCode
                .strip()
                .toUpperCase(Locale.ROOT);

        if (normalized.length()
                > MAX_ERROR_CODE_LENGTH
                || !normalized.matches(
                "[A-Z0-9_]+"
        )) {
            return "UNKNOWN_MODEL_ERROR";
        }

        return normalized;
    }

    /**
     * 处理可以展示给买家的脱敏失败提示。
     */
    private String normalizeSafeMessage(
            String safeMessage
    ) {
        String normalized;

        if (safeMessage == null
                || safeMessage.isBlank()) {
            normalized =
                    "购物助手暂时无法回答，请稍后重试";
        } else {
            normalized = safeMessage.strip();
        }

        int codePoints = normalized.codePointCount(
                0,
                normalized.length()
        );

        if (codePoints
                <= MAX_SAFE_MESSAGE_CODE_POINTS) {
            return normalized;
        }

        int endIndex = normalized.offsetByCodePoints(
                0,
                MAX_SAFE_MESSAGE_CODE_POINTS
        );

        return normalized.substring(0, endIndex);
    }

    private LocalDateTime now() {
        return LocalDateTime.now()
                .truncatedTo(ChronoUnit.MILLIS);
    }

    private void requireOneRow(
            int affectedRows,
            String message
    ) {
        if (affectedRows != 1) {
            throw new BusinessException(
                    50000,
                    message
            );
        }
    }
}
