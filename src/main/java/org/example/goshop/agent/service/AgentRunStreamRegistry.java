package org.example.goshop.agent.service;

import org.example.goshop.agent.dto.AgentSseEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 当前应用实例中正在运行的 Agent SSE 流注册表。
 *
 * <p>数据库负责保证同一个会话最多一个 RUNNING AgentRun；
 * 该注册表负责保证同一个 runId 在当前 JVM 中最多订阅一次模型流。</p>
 *
 * <p>主要解决两个问题：</p>
 *
 * <ol>
 *     <li>浏览器使用相同 clientMessageId 重试时复用原模型流；</li>
 *     <li>首个 SSE 客户端断开后，模型仍继续生成并完成数据库落库。</li>
 * </ol>
 *
 * <p>首期是模块化单体部署，所以使用 JVM 内存注册表。
 * 将来多实例部署时，可以结合 Redis 和消息代理扩展跨实例事件转发。</p>
 */
@Service
public class AgentRunStreamRegistry {

    /**
     * 当前 JVM 中正在运行的 Agent 流
     * key 为 agent_run.id，value 为可以重放的共享 SSE 流。
     */
    private final ConcurrentMap<Long,Flux<AgentSseEvent<?>>> activeStreams =
            new ConcurrentHashMap<>();

    /**
     * 注册或取得指定 runId 的共享流。
     *
     * <p>如果 runId 已经注册，sourceFactory 不会再次执行，
     * 直接返回原来的共享流。</p>
     *
     * <p>sourceFactory 必须返回冷流，并且不能在创建 Flux 时直接调用模型。
     * 真正的模型请求应当发生在订阅阶段。</p>
     */
    public Flux<AgentSseEvent<?>> registerIfAbsent(
            Long runId,
            Supplier<Flux<AgentSseEvent<?>>> sourceFactory
    ) {
        Objects.requireNonNull(
                runId,
                "runId 不能为空"
        );
        Objects.requireNonNull(
                sourceFactory,
                "SSE 源流工厂不能为空"
        );

        /*
         * ConcurrentHashMap.computeIfAbsent 对同一个 runId 原子执行。
         *
         * 即使两个 HTTP 请求同时尝试注册同一个运行，
         * 也只有一个共享流会成为 activeStreams 中的最终值。
         */
        return activeStreams.computeIfAbsent(
                runId,
                ignored -> createSharedStream(
                        runId,
                        sourceFactory
                )
        );
    }

    /**
     * 查询当前 JVM 是否仍持有指定运行的实时流。
     */
    public Optional<Flux<AgentSseEvent<?>>> find(
            Long runId
    ) {
        if (runId == null) {
            return Optional.empty();
        }

        return Optional.ofNullable(
                activeStreams.get(runId)
        );
    }

    /**
     * 当前应用实例中的活动运行数。
     *
     * <p>后续可以用于监控并发数，但不能在日志中输出消息正文。</p>
     */
    public int activeRunCount() {
        return activeStreams.size();
    }

    /**
     * 把一个冷流转换为单次执行且可重放的共享流。
     */
    private Flux<AgentSseEvent<?>> createSharedStream(
            Long runId,
            Supplier<Flux<AgentSseEvent<?>>> sourceFactory
    ) {
        Flux<AgentSseEvent<?>> source =
                Objects.requireNonNull(
                        sourceFactory.get(),
                        "SSE 源流不能为空"
                );

        /*
         * 当 Agent 模型流结束后，安全地从 activeStreams 中删除对应的共享流。
         * 清理回调需要精确删除当前共享流，而不是只根据 runId 删除。
         *
         * 使用 AtomicReference 是因为创建 cleanupSource 时，
         * sharedStream 变量本身还没有构造完成。
         */
        AtomicReference<Flux<AgentSseEvent<?>>>
                // 保存共享流引用的容器
                sharedReference =
                new AtomicReference<>();

        //增加了结束清理逻辑的原始事件流
        // 在原始流尾部增加清理逻辑
        // 在流结束时,把这个runId对应的共享流从activeStreams这个Map里安全移除,只删当前这一个,避免误删或泄漏。
        // 此处只是创建执行逻辑，但还不会执行
        Flux<AgentSseEvent<?>> cleanupSource =
                source.doFinally(signalType -> {
                    Flux<AgentSseEvent<?>> shared =
                            sharedReference.get();

                    if (shared != null) {
                        /*
                         * remove(key, value) 只在映射仍指向这个流时删除，
                         * 不会误删未来可能注册的新流。
                         */
                        activeStreams.remove(
                                runId,
                                shared
                        );
                    }
                });

        /*
         * replay() 会缓存本次运行已经产生的事件。
         *
         * SSE 重连或同 clientMessageId 重试时，新订阅者可以先收到之前
         * 已产生的 RUN_STARTED 和 CONTENT_DELTA，再继续接收实时增量。
         *
         * 运行最长 45 秒且输出 Token 有上限，因此首期允许在单次运行期间
         * 使用 replay() 缓存完整事件。运行结束后映射立即清理。
         */
        //sharedStream真正结束那一刻才会触发前面注册的那个清理动作。
        Flux<AgentSseEvent<?>> sharedStream =
                cleanupSource

                        // 开始缓存已经发出的事件,后面新的订阅者连接上来时能先收到之前的内容,而不是只能看到后面的。
                        .replay()
                        /*
                         * 第一个订阅者到达时连接模型上游一次。
                         *
                         * autoConnect 不会因为下游浏览器断开而取消上游，
                         * 因此模型仍会继续执行最终落库。
                         */
                        // 等有指定数量的订阅者出现时,才真正去连接上游、开始执行。这里传1,就是第一个订阅者一来就开始,后面再来的人都共享这一次执行,不会重复调用LLM。
                        // 上游就是数据的来源,这里指真正调用LLM、产生事件的那个冷流;连接上游,就是正式启动那次调用。
                        // 下游就是接收和处理这些数据的地方,比如前端浏览器、日志系统等订阅这条流的那一端。
                        .autoConnect(1);

        sharedReference.set(sharedStream);

        return sharedStream;
    }


}
