package org.example.goshop.agent.service;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import org.example.goshop.agent.service.model.AgentFailureDescriptor;
import org.example.goshop.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * 把模型调用异常转换为脱敏、稳定的业务错误。
 *
 * <p>绝不能直接把 throwable.getMessage() 返回给前端或保存到
 * agent_message，因为原始异常可能包含模型地址、请求参数或供应商响应。</p>
 */
@Component
public class AgentModelErrorClassifier {

    /**
     * 分析异常原因链并返回安全错误描述。
     */
    public AgentFailureDescriptor classify(
            Throwable throwable
    ) {
        /*
         * Reactor timeout 和底层网络超时可能被包装多层，
         * 所以需要遍历完整原因链。
         */
        if (containsTimeout(throwable)) {
            return new AgentFailureDescriptor(
                    "MODEL_TIMEOUT",
                    "购物助手响应超时，请稍后重试",
                    true,
                    true
            );
        }

        OpenAIServiceException serviceException =
                findCause(
                        throwable,
                        OpenAIServiceException.class
                );

        if (serviceException != null) {
            int statusCode =
                    serviceException.statusCode();

            if (statusCode == 429) {
                return new AgentFailureDescriptor(
                        "MODEL_RATE_LIMIT",
                        "购物助手当前请求较多，请稍后重试",
                        true,
                        false
                );
            }

            if (statusCode == 401
                    || statusCode == 403) {
                /*
                 * 不向买家展示“API Key 无效”等内部配置细节。
                 */
                return new AgentFailureDescriptor(
                        "MODEL_AUTH_ERROR",
                        "购物助手暂时不可用，请联系管理员",
                        false,
                        false
                );
            }

            if (statusCode >= 500) {
                return new AgentFailureDescriptor(
                        "MODEL_UPSTREAM_ERROR",
                        "模型服务暂时不可用，请稍后重试",
                        true,
                        false
                );
            }

            return new AgentFailureDescriptor(
                    "MODEL_REQUEST_REJECTED",
                    "购物助手暂时无法处理该请求",
                    false,
                    false
            );
        }

        /*
         * OpenAIIoException 通常代表连接失败、断流或 DNS/网络问题。
         */
        if (findCause(
                throwable,
                OpenAIIoException.class
        ) != null) {
            return new AgentFailureDescriptor(
                    "MODEL_CONNECTION_ERROR",
                    "模型服务连接失败，请稍后重试",
                    true,
                    false
            );
        }

        BusinessException businessException =
                findCause(
                        throwable,
                        BusinessException.class
                );

        if (businessException != null) {
            /*
             * 50301 在当前链路中可能表示模型返回空内容或超长内容。
             * 仍然只返回固定提示，不透传原始异常正文。
             */
            if (businessException.getCode() == 50301) {
                return new AgentFailureDescriptor(
                        "MODEL_INVALID_RESPONSE",
                        "模型未返回有效回答，请稍后重试",
                        true,
                        false
                );
            }

            return new AgentFailureDescriptor(
                    "AGENT_BUSINESS_ERROR",
                    "购物助手暂时无法完成本次请求",
                    false,
                    false
            );
        }

        /*
         * 未识别异常统一归类，禁止把类名、堆栈或 message 暴露给前端。
         */
        return new AgentFailureDescriptor(
                "AGENT_INTERNAL_ERROR",
                "购物助手暂时不可用，请稍后重试",
                true,
                false
        );
    }

    /**
     * 判断异常原因链中是否存在超时。
     */
    private boolean containsTimeout(
            Throwable throwable
    ) {
        for (Throwable current :
                causeChain(throwable)) {
            if (current instanceof TimeoutException) {
                return true;
            }

            /*
             * 兼容不同 HTTP 客户端自己的 Timeout 异常类型，
             * 但不读取或返回异常正文。
             */
            if (current.getClass()
                    .getSimpleName()
                    .contains("Timeout")) {
                return true;
            }
        }

        return false;
    }

    /**
     * 从异常原因链中寻找指定类型。
     */
    private <T extends Throwable> T findCause(
            Throwable throwable,
            Class<T> expectedType
    ) {
        for (Throwable current :
                causeChain(throwable)) {
            if (expectedType.isInstance(current)) {
                return expectedType.cast(current);
            }
        }

        return null;
    }

    /**
     * 安全构建异常原因链。
     *
     * <p>使用 IdentityHashMap 防止极端情况下出现循环 cause，
     * 导致异常处理本身进入死循环。</p>
     */
    private Iterable<Throwable> causeChain(
            Throwable throwable
    ) {
        if (throwable == null) {
            return Collections.emptyList();
        }

        Set<Throwable> visited =
                Collections.newSetFromMap(
                        new IdentityHashMap<>()
                );

        java.util.List<Throwable> causes =
                new java.util.ArrayList<>();

        Throwable current = throwable;

        while (current != null
                && visited.add(current)
                && causes.size() < 20) {
            causes.add(current);
            current = current.getCause();
        }

        return causes;
    }
}
