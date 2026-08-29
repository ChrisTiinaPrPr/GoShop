package org.example.goshop.agent.service;

import lombok.RequiredArgsConstructor;
import org.example.goshop.agent.dto.*;
import org.example.goshop.agent.entity.AgentRunStatus;
import org.example.goshop.agent.service.model.*;
import org.springframework.ai.chat.messages.Message;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import org.example.goshop.agent.tool.AgentToolRequestContext;
import org.example.goshop.agent.tool.AgentToolEventChannel;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 一次完整 Agent 流的主编排 Service。
 *
 * <p>该类负责串联：</p>
 *
 * <ol>
 *     <li>消息幂等和 AgentRun 初始化；</li>
 *     <li>历史上下文加载；</li>
 *     <li>ChatClient 流式模型调用；</li>
 *     <li>文本增量 SSE 事件；</li>
 *     <li>成功、失败和超时收口；</li>
 *     <li>重复 clientMessageId 的运行复用。</li>
 * </ol>
 *
 * <p>当前事件类型包括：</p>
 *
 * <ul>
 *     <li>RUN_STARTED</li>
 *     <li>TOOL_STARTED</li>
 *     <li>TOOL_COMPLETED</li>
 *     <li>CONTENT_DELTA</li>
 *     <li>MESSAGE_COMPLETED</li>
 *     <li>RUN_FAILED</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "goshop.agent",
        name = "enabled",
        havingValue = "true"
)
@ConditionalOnProperty(
        prefix = "spring.ai.model",
        name = "chat",
        havingValue = "openai"
)
public class AgentRunOrchestrationService {

    /**
     * 识别需要进入服务端确定性工具链的明确加购请求。
     *
     * <p>它只负责路由，不直接创建动作，也不决定 productId/skuId。
     * 真正业务写入仍然只能由 propose_add_cart_item 完成。</p>
     *
     * <p>表达式要求出现明确请求语气或以加购动词直接起句，避免把
     * “为什么无法加入购物车”这类咨询误判为写操作。</p>
     */
    private static final Pattern EXPLICIT_ADD_CART_REQUEST =
            Pattern.compile(
                    "(?:请|帮我|麻烦|给我|替我|我要|我想要|"
                            + "把|将|就|直接|现在)"
                            + "[^。！？\\r\\n]{0,50}"
                            + "(?:(?:加入|添加到|放入|放进|加到)购物车|加购)"
                            + "|"
                            + "(?:^|[，,；;：:\\s])"
                            + "(?:(?:加入|添加到|放入|放进|加到)购物车|加购)"
                            + "(?:吧|一下|一件|一个)?"
                            + "(?:$|[。！？!?，,；;])"
            );

    /**
     * 判断上一轮助手消息是否处于“准备加购/确认卡片”上下文。
     *
     * <p>用户经常不会在每一轮重复说“加入购物车”，例如：</p>
     *
     * <ul>
     *     <li>助手：需要帮您把紫色耳机加入购物车吗？</li>
     *     <li>用户：我要紫色的</li>
     * </ul>
     *
     * <p>该上下文主要约束“可以”“再试试”等没有商品选择信息的短回复。
     * “我要紫色的”这类明确 SKU 选择由独立规则识别，并交给确定性编排器
     * 使用真实商品详情再次校验。</p>
     */
    private static final Pattern ADD_CART_CONTEXT =
            Pattern.compile(
                    "(?:(?:加入|添加到|放入|放进|加到)购物车"
                            + "|加购"
                            + "|待加购确认"
                            + "|待确认(?:卡片|动作|信息)"
                            + "|确认卡片)"
            );

    /**
     * 识别对一次加购失败的重试请求。
     *
     * <p>该表达式不会单独触发写操作，必须同时满足上一轮处于
     * ADD_CART_CONTEXT，避免把普通的“再试试”错误识别为加购。</p>
     */
    private static final Pattern ADD_CART_RETRY_REPLY =
            Pattern.compile(
                    "(?:(?:没有|没|未|还没)"
                            + "[^。！？\\r\\n]{0,12}"
                            + "(?:成功|生成|创建|出现|看到))"
                            + "|"
                            + "(?:(?:再|重新)"
                            + "(?:帮我|给我|帮忙)?"
                            + "[^。！？\\r\\n]{0,8}"
                            + "(?:试试|试一次|来一次|生成|创建|加购))"
            );

    /**
     * 识别用户对上一轮加购邀请的简短肯定答复。
     *
     * <p>“可以”“行”本身不包含商品和写操作语义，只有上一条助手消息
     * 明确处于加购上下文时才允许进入确定性加购链路。</p>
     */
    private static final Pattern ADD_CART_AFFIRMATIVE_REPLY =
            Pattern.compile(
                    "^(?:"
                            + "好(?:的)?"
                            + "|可以"
                            + "|行"
                            + "|没问题"
                            + ")[。！？!?，,]*$"
            );

    /**
     * 识别用户基于上一轮商品详情作出的 SKU 选择。
     *
     * <p>例如“我要紫色的”“我选红轴款”“就这个”。这类话通常省略了
     * “加入购物车”，但已经表达了明确选择。确定性编排器仍会重新执行
     * search_products 和 get_product_detail，并且在无法唯一匹配 SKU 时
     * 只返回澄清提示，不会直接写购物车。</p>
     */
    private static final Pattern ADD_CART_SKU_SELECTION_REPLY =
            Pattern.compile(
                    "^(?:"
                            + "(?:(?:我)?(?:要|选|选择|就要|就选)"
                            + "[^。！？\\r\\n]{0,20}"
                            + "(?:色|款|轴|版|型号|规格|这个|那个|的))"
                            + "|(?:就)?(?:这个|那个|这款|那款)"
                            + ")"
                            + "[。！？!?，,]*$"
            );

    /**
     * 识别加购询问后的裸 SKU 答案。
     *
     * <p>用户看到“需要帮您把哪一款加入购物车”后，常直接回答
     * “黑色款”“星云紫”“红轴版”，不会再次写“我要”或“加入购物车”。
     * 该规则必须与上一轮 ADD_CART_CONTEXT 同时满足，避免把普通商品讨论中
     * 的颜色、型号描述错误路由成写操作。</p>
     */
    private static final Pattern ADD_CART_BARE_SKU_REPLY =
            Pattern.compile(
                    "^[^。！？!?\\r\\n]{1,20}"
                            + "(?:色|款|轴|版|型号|规格)"
                            + "[。！？!?，,]*$"
            );

    /**
     * 重复请求遇到 RUNNING 数据、但注册表暂时还没有共享流时，
     * 等待原请求完成注册的短暂时间。
     */
    private static final Duration REGISTRY_RACE_WAIT =
            Duration.ofMillis(100);

    private final AgentRunInitializationService
            initializationService;

    private final AgentConversationContextService
            contextService;

    private final AgentModelStreamingClient
            modelClient;

    private final AgentRunFinalizationService
            finalizationService;

    private final AgentRunStreamRegistry
            streamRegistry;

    private final AgentModelErrorClassifier
            errorClassifier;

    private final AgentAddCartDeterministicOrchestrator
            addCartOrchestrator;

    private final AgentResultCardPersistenceService
            resultCardPersistenceService;

    /**
     * 创建或复用一条 Agent SSE 事件流。
     *
     * <p>初始化事务在当前 WebMVC 请求线程执行。它只包含短数据库事务，
     * 不包含模型调用，不会长期占用数据库连接。</p>
     */
    public Flux<AgentSseEvent<?>> stream(
            Long userId,
            Long conversationId,
            AgentSendMessageRequest request
    ) {
        /*
         * 这里会完成会话归属校验、clientMessageId 幂等处理，
         * 并创建或找回 AgentRun。
         *
         * 如果会话不存在或请求冲突，异常会在 Controller 返回 Flux 前抛出，
         * 方便统一异常处理器返回普通业务错误。
         */
        AgentRunInitialization initialization =
                initializationService.initialize(
                        userId,
                        conversationId,
                        request,
                        modelClient.modelIdentity()
                );

        if (initialization.newlyCreated()) {
            return registerNewRun(initialization);
        }

        return replayExistingRun(initialization);
    }

    /**
     * 为新 AgentRun 创建并注册唯一共享流。
     */
    private Flux<AgentSseEvent<?>> registerNewRun(
            AgentRunInitialization initialization
    ) {
        Long runId =
                initialization.run().getId();

        return streamRegistry.registerIfAbsent(
                runId,
                /*
                 * Supplier 只创建冷流，不在这里立即调用模型。
                 */
                () -> createNewRunSource(
                        initialization
                )
        );
    }

    /**
     * 创建一条新运行的冷 SSE 源流。
     */
    private Flux<AgentSseEvent<?>> createNewRunSource(
            AgentRunInitialization initialization
    ) {
        /*
         * Flux.defer 确保每个 AgentStreamAccumulator 在上游真正开始时创建。
         * 等到有人订阅时，才创建并执行Flux
         *
         * 注册表会保证这条源流只被订阅一次。
         */
        return Flux.defer(() -> {
            AgentStreamAccumulator accumulator =
                    new AgentStreamAccumulator();

            AgentSseEvent<?> startedEvent =
                    runStartedEvent(
                            initialization,
                            false
                    );

            /*
             * RUN_STARTED 先发送，随后才加载上下文和调用模型。
             *
             * 即使上下文加载失败，前端也已经获得 runId，
             * 后续可以用 RUN_FAILED 对应到同一次运行。
             * Flux.concat 将多条数据流按顺序连接起来
             */
            return Flux.concat(
                    // 创建数据流
                    Flux.just(startedEvent),
                    executeNewRun(
                            initialization,
                            accumulator
                    )
            );
        });
    }

    /**
     * 加载上下文、调用模型并完成数据库收口。
     */
    private Flux<AgentSseEvent<?>> executeNewRun(
            AgentRunInitialization initialization,
            AgentStreamAccumulator accumulator
    ) {
        /*
         * MyBatis 是阻塞式数据库访问。
         *
         * 模型流已经使用 Reactor，因此上下文查询放到 boundedElastic，
         * 避免阻塞模型 HTTP 客户端使用的响应式线程。
         */
        Mono<List<Message>> contextMono =
                // 把同步方法包装成惰性的Mono，buildModelMessages()在订阅时才执行
                // 步骤：订阅 Mono -> 将 buildModelMessages 提交给 boundedElastic 线程池 -> 数据库查询完成 -> 把查询结果继续发送给下游
                Mono.fromCallable(
                                () -> contextService
                                        .buildModelMessages(
                                                initialization
                                        )
                        )
                        // 指定上游任务在哪个线程池执行
                        .subscribeOn(
                                // Schedulers.boundedElastic() 是 Reactor 提供的一个线程调度器，主要用于执行阻塞任务。
                                // 防止 MyBatis 这种阻塞任务占住模型流的响应线程，拖慢其他 Agent 请求。
                                Schedulers.boundedElastic()
                        );

        Flux<AgentSseEvent<?>> normalFlow =
                // 把一个Mono结果转换为 Flux，Mono<List<Message>> -> Flux<String>
                contextMono.flatMapMany(
                        messages -> streamModelDeltas(
                                initialization,
                                messages,
                                accumulator
                        )
                );

        /*
         * 上下文错误、模型异常、超时、空回答、最终落库失败，
         * 都统一进入失败收口。
         */
        return normalFlow.onErrorResume(
                throwable -> finalizeFailure(
                        initialization,
                        accumulator,
                        throwable
                )
        );
    }

    /**
     * 把普通模型增量或确定性加购结果转换为 CONTENT_DELTA，
     * 并在内容流正常结束后完成数据库收口。
     */
    private Flux<AgentSseEvent<?>> streamModelDeltas(
            AgentRunInitialization initialization,
            List<Message> messages,
            AgentStreamAccumulator accumulator
    ) {
        Long conversationId =
                initialization.run()
                        .getConversationId();

        Long runId =
                initialization.run().getId();

        /*
         * 每次新运行创建独立的工具事件通道。
         *
         * 该对象会同时交给：
         * 1. 模型工具执行上下文；
         * 2. 当前 SSE 主编排流。
         */
        AgentToolEventChannel toolEventChannel =
                new AgentToolEventChannel(
                        conversationId,
                        runId
                );

        AgentToolRequestContext toolRequestContext =
                new AgentToolRequestContext(
                        initialization.userId(),
                        conversationId,
                        runId,
                        toolEventChannel
                );

        boolean deterministicAddCartRequest =
                shouldUseDeterministicAddCartFlow(
                        initialization.userMessage()
                                .getContent(),
                        messages
                );

        Flux<AgentModelStreamChunk> modelChunks =
                deterministicAddCartRequest
                        ? Mono.fromCallable(() ->
                                        addCartOrchestrator
                                                .execute(
                                                        initialization
                                                                .userMessage()
                                                                .getContent(),
                                                        messages,
                                                        toolRequestContext
                                                )
                                )
                                /*
                                 * 确定性工具链包含 MyBatis 和商品业务查询，
                                 * 必须离开模型 HTTP 响应线程执行。
                                 */
                                .subscribeOn(
                                        Schedulers.boundedElastic()
                                )
                                /*
                                 * 确定性编排没有调用模型，只能生成纯文本分片，
                                 * Prompt/Completion Token 必须保持为空。
                                 */
                                .map(AgentModelStreamChunk::textOnly)
                                .flux()
                        : modelClient.stream(
                                messages,
                                toolRequestContext
                        );

        Flux<AgentSseEvent<?>> deltaEvents =
                modelChunks
                        .<AgentSseEvent<?>>handle((chunk, sink) -> {
                            /*
                             * 普通问答由模型返回流式片段；明确加购由确定性
                             * 工具链返回一段固定安全文案。两者统一交给累加器，
                             * 以便同时保存正文、Token Usage 和首字延迟。
                             */
                            accumulator.accept(chunk);

                            /*
                             * 只有 Usage 的结束分片也必须进入累加器，但不能
                             * 向前端发送空 CONTENT_DELTA。
                             */
                            if (!chunk.hasContent()) {
                                return;
                            }

                            sink.next(AgentSseEvent.create(
                                    AgentSseEventType.CONTENT_DELTA,
                                    conversationId,
                                    runId,
                                    // 表示事件的具体数据，其中只保存本次新增的文本片段。
                                    new AgentSseData.ContentDeltaData(
                                            chunk.contentDelta()
                                    )
                            ));
                        })
                        .doFinally(signalType ->
                                toolEventChannel.complete()
                        );

        /*
         * 只有模型流正常完成后才执行 complete。
         * complete 是阻塞数据库事务，因此切换到 boundedElastic。
         */
        Mono<AgentSseEvent<?>> completedEvent =
                Mono.fromCallable(
                                () -> {
                                    String assistantContent =
                                            accumulator.content();

                                    return finalizationService
                                            .complete(
                                                    runId,
                                                    assistantContent,
                                                    accumulator
                                                            .metrics()
                                            );
                                }
                        )
                        .subscribeOn(
                                Schedulers.boundedElastic()
                        )
                        .map(finalization ->
                                terminalEvent(
                                        conversationId,
                                        runId,
                                        finalization,
                                        false
                                )
                        );

        /*
         * 工具事件和模型文本增量属于两个同时活动的来源，
         * 因此必须使用 merge。
         *
         * 不能使用 concat：
         * 工具事件通道要等模型结束才关闭，如果先 concat 工具流，
         * 模型文本流将永远无法开始订阅。
         */
        Flux<AgentSseEvent<?>> liveEvents =
                Flux.merge(
                        toolEventChannel.events(),
                        deltaEvents
                );

        /*
         * MESSAGE_COMPLETED 必须等待：
         *
         * 1. 模型文本流结束；
         * 2. 工具事件通道关闭；
         * 3. 最终助手消息成功落库。
         */
        return liveEvents.concatWith(
                completedEvent
        );
    }

    /**
     * 判断本轮是否必须进入服务端确定性加购链路。
     *
     * <p>这里仅判断路由，不选择 productId/skuId，也不直接创建动作。
     * 商品与 SKU 仍由确定性编排器通过本轮实时工具结果决定。</p>
     *
     * <p>包级可见性用于回归测试以下三类输入：</p>
     *
     * <ul>
     *     <li>当前消息直接要求加入购物车；</li>
     *     <li>用户基于上一轮商品详情选择颜色、型号或规格；</li>
     *     <li>上一轮处于加购上下文，用户肯定或要求重试。</li>
     * </ul>
     */
    static boolean shouldUseDeterministicAddCartFlow(
            String userContent,
            List<Message> messages
    ) {
        if (userContent == null || userContent.isBlank()) {
            return false;
        }

        String normalizedUserContent = userContent.strip();

        if (EXPLICIT_ADD_CART_REQUEST
                .matcher(normalizedUserContent)
                .find()) {
            return true;
        }

        String latestAssistantContent =
                findLatestAssistantContent(messages);

        if (latestAssistantContent.isBlank()) {
            return false;
        }

        /*
         * “我要紫色的”本身已经是对上一轮商品规格的明确选择。
         * 即使上一条助手回复没有再次写出“加入购物车”，也应进入确定性
         * 链路；若数据库中没有可恢复的商品详情，编排器会安全地要求澄清。
         */
        if (ADD_CART_SKU_SELECTION_REPLY
                .matcher(normalizedUserContent)
                .matches()) {
            return true;
        }

        boolean previousAssistantInAddCartContext =
                ADD_CART_CONTEXT
                        .matcher(latestAssistantContent)
                        .find();

        if (!previousAssistantInAddCartContext) {
            return false;
        }

        return ADD_CART_AFFIRMATIVE_REPLY
                .matcher(normalizedUserContent)
                .matches()
                || ADD_CART_BARE_SKU_REPLY
                .matcher(normalizedUserContent)
                .matches()
                || ADD_CART_RETRY_REPLY
                .matcher(normalizedUserContent)
                .find();
    }

    /**
     * 读取当前用户消息之前最近一条助手正文，只用于判断对话意图。
     *
     * <p>正文不能提供 productId、skuId、价格或库存。真正业务参数仍必须
     * 来自当前 Run 的工具结果，因此这里不会把模型文字变成业务事实。</p>
     */
    private static String findLatestAssistantContent(
            List<Message> messages
    ) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }

        for (int index = messages.size() - 1;
             index >= 0;
             index--) {
            Message message = messages.get(index);

            if (message instanceof AssistantMessage assistantMessage
                    && assistantMessage.getText() != null
                    && !assistantMessage.getText().isBlank()) {
                return assistantMessage.getText();
            }
        }

        return "";
    }

    /**
     * 模型、上下文或完成事务失败后的统一处理。
     */
    private Flux<AgentSseEvent<?>> finalizeFailure(
            AgentRunInitialization initialization,
            AgentStreamAccumulator accumulator,
            Throwable throwable
    ) {
        AgentFailureDescriptor failure =
                errorClassifier.classify(throwable);

        Long conversationId =
                initialization.run()
                        .getConversationId();

        Long runId =
                initialization.run().getId();

        Mono<AgentRunFinalization> finalizationMono =
                Mono.fromCallable(() -> {
                            if (failure.timedOut()) {
                                return finalizationService
                                        .timeout(
                                                runId,
                                                failure.safeMessage(),
                                                accumulator.durationMs()
                                        );
                            }

                            return finalizationService.fail(
                                    runId,
                                    failure.errorCode(),
                                    failure.safeMessage(),
                                    accumulator.durationMs()
                            );
                        })
                        .subscribeOn(
                                Schedulers.boundedElastic()
                        );

        return finalizationMono
                .<AgentSseEvent<?>>map(finalization ->
                        terminalEvent(
                                conversationId,
                                runId,
                                finalization,
                                failure.retryable()
                        )
                )
                .onErrorReturn(
                        genericRunFailedEvent(
                                conversationId,
                                runId
                        )
                )
                .flux();
    }

    /**
     * 处理重复 clientMessageId。
     */
    private Flux<AgentSseEvent<?>> replayExistingRun(
            AgentRunInitialization initialization
    ) {
        AgentRunStatus status =
                initialization.run().getStatus();

        if (status == AgentRunStatus.RUNNING) {
            /*
             * 使用 defer 把注册表查询延迟到浏览器真正订阅时。
             *
             * 这可以避开原请求刚提交初始化事务、尚未来得及把共享流放进
             * 注册表的极短竞态窗口。
             */
            return Flux.defer(
                    () -> findRunningStreamOrRecover(
                            initialization
                    )
            );
        }

        if (status == AgentRunStatus.COMPLETED) {
            return replayCompletedRun(
                    initialization
            );
        }

        return replayFailedRun(initialization);
    }

    /**
     * 复用当前 JVM 中仍在执行的共享流。
     */
    private Flux<AgentSseEvent<?>>
    findRunningStreamOrRecover(
            AgentRunInitialization initialization
    ) {
        Long runId =
                initialization.run().getId();

        return streamRegistry.find(runId)
                .orElseGet(() ->
                        Mono.delay(REGISTRY_RACE_WAIT)
                                .flatMapMany(ignored ->
                                        streamRegistry
                                                .find(runId)
                                                .orElseGet(
                                                        () -> recoverLostRun(
                                                                initialization
                                                        )
                                                )
                                )
                );
    }

    /**
     * 数据库是 RUNNING，但 JVM 中没有实时流。
     *
     * <p>常见原因是应用在模型生成过程中重启。此时无法重新调用模型，
     * 否则可能重复执行未来加入的业务工具，所以把原运行安全收口为失败。</p>
     */
    private Flux<AgentSseEvent<?>> recoverLostRun(
            AgentRunInitialization initialization
    ) {
        Long conversationId =
                initialization.run()
                        .getConversationId();

        Long runId =
                initialization.run().getId();

        AgentSseEvent<?> started =
                runStartedEvent(
                        initialization,
                        true
                );

        Mono<AgentSseEvent<?>> failed =
                Mono.fromCallable(
                                () -> finalizationService.fail(
                                        runId,
                                        "RUN_STREAM_LOST",
                                        "购物助手运行已中断，请重新发送消息",
                                        null
                                )
                        )
                        .subscribeOn(
                                Schedulers.boundedElastic()
                        )
                        .<AgentSseEvent<?>>map(finalization ->
                                terminalEvent(
                                        conversationId,
                                        runId,
                                        finalization,
                                        true
                                )
                        )
                        .onErrorReturn(
                                genericRunFailedEvent(
                                        conversationId,
                                        runId
                                )
                        );

        return Flux.concat(
                Flux.just(started),
                failed
        );
    }

    /**
     * 重放已经成功落库的运行。
     */
    private Flux<AgentSseEvent<?>> replayCompletedRun(
            AgentRunInitialization initialization
    ) {
        Long conversationId =
                initialization.run()
                        .getConversationId();

        Long runId =
                initialization.run().getId();

        String content =
                initialization.assistantMessage()
                        .getContent();

        AgentSseEvent<?> started =
                runStartedEvent(
                        initialization,
                        true
                );

        AgentSseEvent<?> delta =
                AgentSseEvent.create(
                        AgentSseEventType.CONTENT_DELTA,
                        conversationId,
                        runId,
                        new AgentSseData.ContentDeltaData(
                                content
                        )
                );

        AgentSseEvent<?> completed =
                AgentSseEvent.create(
                        AgentSseEventType
                                .MESSAGE_COMPLETED,
                        conversationId,
                        runId,
                        new AgentSseData
                                .MessageCompletedData(
                                resultCardPersistenceService.toResponse(
                                        initialization
                                                .assistantMessage()
                                )
                        )
                );

        return Flux.just(
                started,
                delta,
                completed
        );
    }

    /**
     * 重放已经失败或超时的运行。
     */
    private Flux<AgentSseEvent<?>> replayFailedRun(
            AgentRunInitialization initialization
    ) {
        Long conversationId =
                initialization.run()
                        .getConversationId();

        Long runId =
                initialization.run().getId();

        AgentSseEvent<?> started =
                runStartedEvent(
                        initialization,
                        true
                );

        AgentSseEvent<?> failed =
                AgentSseEvent.create(
                        AgentSseEventType.RUN_FAILED,
                        conversationId,
                        runId,
                        new AgentSseData.RunFailedData(
                                50301,
                                initialization
                                        .assistantMessage()
                                        .getContent(),
                                isRetryableError(
                                        initialization.run()
                                                .getErrorCode()
                                )
                        )
                );

        return Flux.just(started, failed);
    }

    /**
     * 根据最终数据库状态创建最后一个 SSE 事件。
     *
     * <p>收口 Service 可能返回 stateChanged=false，例如超时事务已经
     * 先于模型完成事务执行。因此不能假设调用 complete 就一定成功。</p>
     */
    private AgentSseEvent<?> terminalEvent(
            Long conversationId,
            Long runId,
            AgentRunFinalization finalization,
            boolean retryable
    ) {
        if (finalization.status()
                == AgentRunStatus.COMPLETED) {
            return AgentSseEvent.create(
                    AgentSseEventType
                            .MESSAGE_COMPLETED,
                    conversationId,
                    runId,
                    new AgentSseData
                            .MessageCompletedData(
                            finalization
                                    .assistantMessage()
                    )
            );
        }

        return AgentSseEvent.create(
                AgentSseEventType.RUN_FAILED,
                conversationId,
                runId,
                new AgentSseData.RunFailedData(
                        50301,
                        finalization
                                .assistantMessage()
                                .content(),
                        retryable
                )
        );
    }

    /**
     * RUN_STARTED 事件。
     */
    private AgentSseEvent<?> runStartedEvent(
            AgentRunInitialization initialization,
            boolean replayed
    ) {
        return AgentSseEvent.create(
                AgentSseEventType.RUN_STARTED,
                initialization.run()
                        .getConversationId(),
                initialization.run().getId(),
                new AgentSseData.RunStartedData(
                        initialization.userMessage()
                                .getId(),
                        initialization.assistantMessage()
                                .getId(),
                        replayed
                )
        );
    }

    /**
     * 失败收口自身异常时的最后降级事件。
     */
    private AgentSseEvent<?> genericRunFailedEvent(
            Long conversationId,
            Long runId
    ) {
        return AgentSseEvent.create(
                AgentSseEventType.RUN_FAILED,
                conversationId,
                runId,
                new AgentSseData.RunFailedData(
                        50301,
                        "购物助手暂时不可用，请稍后重试",
                        true
                )
        );
    }

    /**
     * 根据持久化错误分类判断重放时是否建议重试。
     */
    private boolean isRetryableError(
            String errorCode
    ) {
        if (errorCode == null) {
            return true;
        }

        return !errorCode.equals(
                "MODEL_AUTH_ERROR"
        ) && !errorCode.equals(
                "MODEL_REQUEST_REJECTED"
        ) && !errorCode.equals(
                "AGENT_BUSINESS_ERROR"
        );
    }


}
