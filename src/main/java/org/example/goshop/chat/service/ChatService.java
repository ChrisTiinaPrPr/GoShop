package org.example.goshop.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.example.goshop.auth.entity.SysUser;
import org.example.goshop.auth.mapper.SysUserMapper;
import org.example.goshop.chat.dto.*;
import org.example.goshop.chat.entity.ChatConversation;
import org.example.goshop.chat.entity.ChatMessage;
import org.example.goshop.chat.mapper.ChatConversationMapper;
import org.example.goshop.chat.mapper.ChatMessageMapper;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.merchant.entity.Merchant;
import org.example.goshop.merchant.mapper.MerchantMapper;
import org.example.goshop.order.entity.MallOrder;
import org.example.goshop.order.entity.OrderItem;
import org.example.goshop.order.mapper.MallOrderMapper;
import org.example.goshop.order.mapper.OrderItemMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.goshop.chat.event.ChatMessageCreatedEvent;
import org.example.goshop.chat.event.ChatReadAdvancedEvent;
import org.example.goshop.product.dto.PageResult;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ChatService {

    private static final String BUYER_ROLE = "USER";
    private static final String MERCHANT_ROLE = "MERCHANT";

    private final ChatConversationMapper conversationMapper;
    private final ChatMessageMapper messageMapper;
    private final MerchantMapper merchantMapper;
    private final SysUserMapper sysUserMapper;
    private final MallOrderMapper mallOrderMapper;
    private final OrderItemMapper orderItemMapper;
    /**
     * 负责聊天图片格式校验、私有 OSS 上传和签名 URL 生成。
     */
    private final ChatImageStorageService chatImageStorageService;

    /**
     * 发布 Spring 进程内事务事件。
     *
     * <p>监听器使用 AFTER_COMMIT，因此这里只是登记事件，
     * 不会在事务提交前向 WebSocket 推送。</p>
     */
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 买家根据商家 ID 创建或复用唯一会话。
     *
     * <p>数据库唯一键 (buyer_user_id, merchant_id) 是最终并发保护。
     * 即使用户快速点击两次，也只会存在一条会话。</p>
     */
    @Transactional
    public ChatConversationResponse getOrCreateConversation(Long buyerUserId, CreateChatConversationRequest request) {

        SysUser buyer = requireActiveUser(buyerUserId);
        Merchant merchant = requireActiveMerchantById(request.merchantId());

        ChatConversation existing = conversationMapper.selectByBuyerAndMerchant(
                buyerUserId,
                merchant.getId()
        );

        if (existing != null) {
            return toBuyerConversationResponse(existing, buyer, merchant);
        }

        ChatConversation conversation = new ChatConversation();
        conversation.setBuyerUserId(buyerUserId);
        conversation.setMerchantId(merchant.getId());

        try {
            conversationMapper.insert(conversation);
        } catch (DuplicateKeyException duplicate) {
            /*
             * 两个并发请求都可能在 insert 前查询不到会话。
             * 第一个请求插入成功，第二个请求命中数据库唯一键。
             * 此时重新读取已有会话即可，不能向用户返回服务器错误。
             */
            ChatConversation concurrentResult = conversationMapper.selectByBuyerAndMerchant(
                    buyerUserId,
                    merchant.getId()
            );

            if (concurrentResult == null) {
                throw new BusinessException(50000, "创建聊天会话失败，请稍后重试");
            }
            return toBuyerConversationResponse(
                    concurrentResult,
                    buyer,
                    merchant
            );
        }

        // createdAt 由数据库生成，重新查询后相应字段才完整。
        ChatConversation saved = conversationMapper.selectById(conversation.getId());
        return toBuyerConversationResponse(saved, buyer, merchant);
    }

    /**
     * 买家发送 TEXT 消息。
     */
    @Transactional
    public ChatMessageResponse sendBuyerMessage(
            Long buyerUserId,
            Long conversationId,
            SendChatMessageRequest request
    ) {
        SysUser buyer = requireActiveUser(buyerUserId);

        ChatConversation conversation = conversationMapper.selectBuyerConversationForUpdate(
                conversationId,
                buyerUserId
        );

        if (conversation == null) {
            // 不区分“会话不存在”和“无权访问”，避免泄漏其他用户的会话信息。
            throw new BusinessException(40401, "聊天会话不存在或无权访问");
        }

        Merchant merchant = requireActiveMerchantById(conversation.getMerchantId());

        return saveMessage(
                conversation,
                buyer,
                merchant,
                BUYER_ROLE,
                request
        );
    }

    /**
     * 买家上传图片并创建 IMAGE 消息。
     */
    @Transactional
    public ChatMessageResponse sendBuyerImageMessage(
            Long buyerUserId,
            Long conversationId,
            ChatImageMessageRequest request
    ) {
        SysUser buyer = requireActiveUser(buyerUserId);

        ChatConversation conversation =
                conversationMapper.selectBuyerConversationForUpdate(
                        conversationId,
                        buyerUserId
                );

        if (conversation == null) {
            throw new BusinessException(
                    40401,
                    "聊天会话不存在或无权访问"
            );
        }

        Merchant merchant = requireActiveMerchantById(
                conversation.getMerchantId()
        );

        return saveImageMessage(
                conversation,
                buyer,
                merchant,
                BUYER_ROLE,
                request
        );
    }

    /**
     * 商家发送 TEXT 消息。
     *
     * <p>merchantId 只能根据当前 JWT 中的 userId 查询，
     * 不能由前端传入。</p>
     */
    @Transactional
    public ChatMessageResponse sendMerchantMessage(
            Long merchantUserId,
            Long conversationId,
            SendChatMessageRequest request
    ) {
        SysUser merchantUser = requireActiveUser(merchantUserId);
        Merchant merchant = requireActiveMerchantByUserId(merchantUserId);

        ChatConversation conversation = conversationMapper.selectMerchantConversationForUpdate(
                conversationId,
                merchant.getId()
        );

        if (conversation == null) {
            throw new BusinessException(40401, "聊天会话不存在或无权访问");
        }

        SysUser buyer = requireActiveUser(conversation.getBuyerUserId());

        return saveMessage(
                conversation,
                buyer,
                merchant,
                MERCHANT_ROLE,
                request
        );
    }

    /**
     * 商家上传图片并创建 IMAGE 消息。
     */
    @Transactional
    public ChatMessageResponse sendMerchantImageMessage(
            Long merchantUserId,
            Long conversationId,
            ChatImageMessageRequest request
    ) {
        requireActiveUser(merchantUserId);

        Merchant merchant =
                requireActiveMerchantByUserId(merchantUserId);

        ChatConversation conversation =
                conversationMapper.selectMerchantConversationForUpdate(
                        conversationId,
                        merchant.getId()
                );

        if (conversation == null) {
            throw new BusinessException(
                    40401,
                    "聊天会话不存在或无权访问"
            );
        }

        SysUser buyer = requireActiveUser(
                conversation.getBuyerUserId()
        );

        return saveImageMessage(
                conversation,
                buyer,
                merchant,
                MERCHANT_ROLE,
                request
        );
    }

    /**
     * 保存聊天消息的公共事务逻辑。
     *
     * <p>当前支持：</p>
     * <ul>
     *     <li>TEXT：文字消息</li>
     *     <li>ORDER：订单卡片消息</li>
     * </ul>
     *
     * <p>调用该方法之前，会话行已经通过 SELECT ... FOR UPDATE 加锁，
     * 因此同一个会话中的消息插入、会话摘要更新是串行执行的。</p>
     */
    private ChatMessageResponse saveMessage(
            ChatConversation conversation,
            SysUser buyer,
            Merchant merchant,
            String senderRole,
            SendChatMessageRequest request
    ) {
        /*
         * 第一步：根据消息类型校验和整理消息内容。
         *
         * TEXT：
         * - 去除文字首尾空格；
         * - 得到 textContent；
         * - order 为 null。
         *
         * ORDER：
         * - 校验订单号；
         * - 校验订单属于当前买家和当前商家；
         * - 得到真实 MallOrder；
         * - textContent 为 null。
         *
         * 这一步必须放在幂等查询之前，因为后面的幂等校验
         * 需要使用整理后的文字内容或真实 orderId。
         */
        PreparedMessage prepared = prepareMessage(request, conversation);

        /*
         * UUID 统一转为小写。
         *
         * 数据库使用 ascii_bin 大小写敏感排序时，
         * 同一个 UUID 的大小写形式可能被当成两个不同的幂等键。
         */
        String clientMessageId = request.clientMessageId()
                .toLowerCase(Locale.ROOT);

        /*
         * chat_message.sender_user_id 保存的是 sys_user.id。
         *
         * 买家发送消息：
         * senderUserId 使用 buyer.id。
         *
         * 商家发送消息：
         * senderUserId 使用 merchant.userId，
         * 不能使用 merchant.id，因为两者属于不同表。
         */
        Long senderUserId = BUYER_ROLE.equals(senderRole)
                ? buyer.getId()
                : merchant.getUserId();

        /*
         * 在插入数据库前先查询幂等记录。
         *
         * 客户端因为网络超时重复提交相同 clientMessageId 时，
         * 应直接返回第一次创建的消息，不能重复插入。
         */
        ChatMessage existing = messageMapper.selectByIdempotencyKey(
                senderUserId,
                senderRole,
                clientMessageId
        );

        if (existing != null) {
            /*
             * 查到相同 UUID 还不够，还要检查消息类型和消息内容。
             *
             * 例如：
             * - 相同 UUID + 相同订单：属于正常重试；
             * - 相同 UUID + 不同订单：属于 UUID 使用冲突。
             */
            validateIdempotentRequest(
                    existing,
                    conversation.getId(),
                    prepared
            );

            /*
             * 幂等请求直接返回原来的消息。
             * 不再插入消息，也不重复更新会话摘要和未读消息。
             */
            return toMessageResponse(
                    existing,
                    conversation,
                    buyer,
                    merchant
            );
        }

        /*
         * 创建聊天消息实体。
         */
        ChatMessage message = new ChatMessage();

        message.setConversationId(conversation.getId());
        message.setSenderUserId(senderUserId);
        message.setSenderRole(senderRole);
        message.setClientMessageId(clientMessageId);

        /*
         * 不再固定写 ChatMessageType.TEXT。
         *
         * prepared.type() 可能是：
         * - ChatMessageType.TEXT
         * - ChatMessageType.ORDER
         */
        message.setMessageType(prepared.type());

        /*
         * TEXT 消息的 textContent 有值。
         * ORDER 消息的 textContent 为 null。
         */
        message.setTextContent(prepared.textContent());

        /*
         * ORDER 消息保存经过服务端校验后的订单主键。
         *
         * 不要保存前端直接提交的 orderNo，
         * 因为 prepared.order() 是服务端根据订单号、买家和商家
         * 三个条件查询出来的可信订单。
         *
         * TEXT 消息没有关联订单，所以 orderId 为 null。
         */
        message.setOrderId(
                prepared.order() == null
                        ? null
                        : prepared.order().getId()
        );

        /*
         * IMAGE 消息当前还没有实现，因此图片相关字段保持 null。
         * 不需要主动调用 setImageObjectKey(null)。
         */

        /*
         * 主动设置消息创建时间。
         *
         * 消息的 createdAt 和会话的 lastMessageAt 使用同一个时间，
         * 避免数据库时间和 Java 时间产生微小偏差。
         *
         * 数据库字段是 DATETIME(3)，因此截断到毫秒。
         */
        message.setCreatedAt(
                LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS)
        );

        try {
            int inserted = messageMapper.insert(message);

            if (inserted != 1) {
                throw new BusinessException(50000, "保存消息失败");
            }
        } catch (DuplicateKeyException duplicate) {
            /*
             * 处理并发幂等请求。
             *
             * 两个请求可能几乎同时查询，都没有查到已有消息，
             * 然后同时执行 INSERT。
             *
             * 数据库唯一键只允许其中一个请求插入成功；
             * 另一个请求进入这里，重新查询第一次插入的消息。
             */
            ChatMessage concurrentResult =
                    messageMapper.selectByIdempotencyKey(
                            senderUserId,
                            senderRole,
                            clientMessageId
                    );

            if (concurrentResult == null) {
                throw new BusinessException(
                        50000,
                        "聊天消息幂等状态异常"
                );
            }

            /*
             * 即使数据库出现唯一键冲突，
             * 也要检查它是否确实是同一次请求。
             */
            validateIdempotentRequest(
                    concurrentResult,
                    conversation.getId(),
                    prepared
            );

            return toMessageResponse(
                    concurrentResult,
                    conversation,
                    buyer,
                    merchant
            );
        }

        /*
         * 消息保存成功后，更新会话的最后一条消息。
         *
         * 消息插入和会话更新位于同一个事务中：
         * 如果会话摘要更新失败，前面插入的消息也会回滚。
         */
        int updated = conversationMapper.advanceLastMessage(
                conversation.getId(),
                message.getId(),
                message.getCreatedAt()
        );

        if (updated != 1) {
            throw new BusinessException(
                    50000,
                    "更新聊天会话摘要失败"
            );
        }

        /*
         * advanceLastMessage 是 Mapper 自定义 UPDATE。
         * 它只更新数据库，不会自动修改当前 conversation Java 对象。
         *
         * 所以这里手动同步对象，后面构建 WebSocket 会话摘要时才能
         * 读取到最新的 lastMessageId 和 lastMessageAt。
         */
        conversation.setLastMessageId(message.getId());
        conversation.setLastMessageAt(message.getCreatedAt());

        /*
         * 将刚保存的消息转换为统一响应。
         *
         * TEXT 会填写 content；
         * ORDER 会填写 orderCard。
         */
        ChatMessageResponse messageResponse =
                toMessageResponse(
                        message,
                        conversation,
                        buyer,
                        merchant
                );

        /*
         * 分别构建买家端和商家端看到的会话摘要。
         *
         * 两端的对方资料和未读数不同，所以需要分别构建。
         */
        ChatConversationResponse buyerConversation =
                toBuyerConversationResponse(
                        conversation,
                        buyer,
                        merchant
                );

        ChatConversationResponse merchantConversation =
                toMerchantConversationResponse(
                        conversation,
                        buyer,
                        merchant
                );

        /*
         * 发布进程内聊天事件。
         *
         * 监听器使用 AFTER_COMMIT：
         * - 当前数据库事务提交成功后才发送 WebSocket；
         * - 如果事务回滚，则不会向客户端推送一条不存在的消息。
         *
         * 这里不使用 RabbitMQ，因为聊天消息的事实来源是 MySQL，
         * WebSocket 仅负责实时提醒。
         */
        eventPublisher.publishEvent(
                new ChatMessageCreatedEvent(
                        buyer.getId(),
                        merchant.getId(),
                        messageResponse,
                        buyerConversation,
                        merchantConversation
                )
        );

        return messageResponse;
    }

    /**
     * 上传图片并保存 IMAGE 消息。
     *
     * <p>处理顺序：</p>
     * <ol>
     *     <li>查询幂等消息，正常重试不重复上传；</li>
     *     <li>上传私有 OSS；</li>
     *     <li>保存 IMAGE 消息；</li>
     *     <li>更新会话最后消息；</li>
     *     <li>事务提交后通过 WebSocket 推送。</li>
     * </ol>
     */
    private ChatMessageResponse saveImageMessage(
            ChatConversation conversation,
            SysUser buyer,
            Merchant merchant,
            String senderRole,
            ChatImageMessageRequest request
    ) {
        String clientMessageId = request.clientMessageId()
                .toLowerCase(Locale.ROOT);

        Long senderUserId = BUYER_ROLE.equals(senderRole)
                ? buyer.getId()
                : merchant.getUserId();

        /*
         * 网络重试时先查询已有消息。
         * 如果存在，就不再重复上传 OSS。
         */
        ChatMessage existing =
                messageMapper.selectByIdempotencyKey(
                        senderUserId,
                        senderRole,
                        clientMessageId
                );

        if (existing != null) {
            validateImageIdempotentRequest(
                    existing,
                    conversation.getId()
            );

            return toMessageResponse(
                    existing,
                    conversation,
                    buyer,
                    merchant
            );
        }

        ChatImageStorageService.UploadResult uploadResult =
                chatImageStorageService.upload(
                        conversation.getId(),
                        request.file()
                );

        /*
         * 如果后面的数据库事务回滚，包括提交阶段失败，
         * 删除本次上传但没有成功关联到消息的 OSS 对象。
         */
        registerImageRollbackCleanup(uploadResult.objectKey());

        ChatMessage message = new ChatMessage();
        message.setConversationId(conversation.getId());
        message.setSenderUserId(senderUserId);
        message.setSenderRole(senderRole);
        message.setMessageType(ChatMessageType.IMAGE);

        // IMAGE 消息不能填写文字和订单字段。
        message.setTextContent(null);
        message.setOrderId(null);

        // 数据库只保存私有对象键和非敏感元数据。
        message.setImageObjectKey(uploadResult.objectKey());
        message.setImageMetaJson(uploadResult.metadataJson());

        message.setClientMessageId(clientMessageId);
        message.setCreatedAt(
                LocalDateTime.now()
                        .truncatedTo(ChronoUnit.MILLIS)
        );

        try {
            int inserted = messageMapper.insert(message);

            if (inserted != 1) {
                throw new BusinessException(
                        50000,
                        "保存图片消息失败"
                );
            }
        } catch (DuplicateKeyException duplicate) {
            /*
             * 极小概率下，两个相同 UUID 请求可能同时通过首次查询，
             * 并且都完成了 OSS 上传。
             *
             * 数据库唯一键只允许一条消息写入。
             * 当前请求上传的多余对象需要立即删除。
             */
            chatImageStorageService.deleteQuietly(
                    uploadResult.objectKey()
            );

            ChatMessage concurrentResult =
                    messageMapper.selectByIdempotencyKey(
                            senderUserId,
                            senderRole,
                            clientMessageId
                    );

            if (concurrentResult == null) {
                throw new BusinessException(
                        50000,
                        "图片消息幂等状态异常"
                );
            }

            validateImageIdempotentRequest(
                    concurrentResult,
                    conversation.getId()
            );

            return toMessageResponse(
                    concurrentResult,
                    conversation,
                    buyer,
                    merchant
            );
        }

        int updated = conversationMapper.advanceLastMessage(
                conversation.getId(),
                message.getId(),
                message.getCreatedAt()
        );

        if (updated != 1) {
            /*
             * 当前事务会回滚，registerImageRollbackCleanup()
             * 会在事务完成后删除刚上传的 OSS 图片。
             */
            throw new BusinessException(
                    50000,
                    "更新聊天会话摘要失败"
            );
        }

        conversation.setLastMessageId(message.getId());
        conversation.setLastMessageAt(message.getCreatedAt());

        ChatMessageResponse messageResponse =
                toMessageResponse(
                        message,
                        conversation,
                        buyer,
                        merchant
                );

        ChatConversationResponse buyerConversation =
                toBuyerConversationResponse(
                        conversation,
                        buyer,
                        merchant
                );

        ChatConversationResponse merchantConversation =
                toMerchantConversationResponse(
                        conversation,
                        buyer,
                        merchant
                );

        /*
         * 监听器使用 AFTER_COMMIT。
         * 数据库提交失败时不会推送不存在的图片消息。
         */
        eventPublisher.publishEvent(
                new ChatMessageCreatedEvent(
                        buyer.getId(),
                        merchant.getId(),
                        messageResponse,
                        buyerConversation,
                        merchantConversation
                )
        );

        return messageResponse;
    }

    /**
     * 检查相同 clientMessageId 是否对应同一条图片消息。
     *
     * <p>图片接口采用“第一次成功写入为准”：</p>
     * <ul>
     *     <li>同一会话、同一 UUID 重试：返回第一次图片消息；</li>
     *     <li>UUID 已用于其他会话或其他消息类型：返回 40901。</li>
     * </ul>
     */
    private void validateImageIdempotentRequest(
            ChatMessage existing,
            Long conversationId
    ) {
        boolean sameRequest =
                Objects.equals(
                        existing.getConversationId(),
                        conversationId
                )
                        && existing.getMessageType()
                        == ChatMessageType.IMAGE;

        if (!sameRequest) {
            throw new BusinessException(
                    40901,
                    "clientMessageId 已被其他聊天请求使用"
            );
        }
    }

    /**
     * 数据库事务没有提交成功时删除刚上传的 OSS 对象。
     *
     * <p>不能在消息插入后立刻认为业务成功，因为事务可能在最终
     * commit 阶段失败。</p>
     */
    private void registerImageRollbackCleanup(String objectKey) {
        if (!TransactionSynchronizationManager
                .isSynchronizationActive()) {

            /*
             * 图片消息必须运行在事务中。
             * 如果事务同步没有开启，立即清理对象，避免产生孤儿文件。
             */
            chatImageStorageService.deleteQuietly(objectKey);

            throw new BusinessException(
                    50000,
                    "图片消息事务状态异常"
            );
        }

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status
                                != TransactionSynchronization.STATUS_COMMITTED) {

                            chatImageStorageService.deleteQuietly(
                                    objectKey
                            );
                        }
                    }
                }
        );
    }

    /**
     * 判断重复 clientMessageId 是否真的是同一次请求。
     *
     * <p>如果 UUID 相同但会话或正文不同，说明客户端错误复用了幂等键，
     * 不能把旧消息当作本次请求成功结果返回。</p>
     */
    private void validateIdempotentRequest(
            ChatMessage existing,
            Long conversationId,
            PreparedMessage prepared
    ) {
        boolean sameConversation =
                Objects.equals(existing.getConversationId(), conversationId);

        boolean sameType =
                existing.getMessageType() == prepared.type();

        boolean samePayload;

        if (prepared.type() == ChatMessageType.TEXT) {
            samePayload = Objects.equals(
                    existing.getTextContent(),
                    prepared.textContent()
            );
        } else if (prepared.type() == ChatMessageType.ORDER) {
            Long requestedOrderId = prepared.order() == null
                    ? null
                    : prepared.order().getId();

            samePayload = Objects.equals(
                    existing.getOrderId(),
                    requestedOrderId
            );
        } else {
            samePayload = false;
        }

        if (!sameConversation || !sameType || !samePayload) {
            throw new BusinessException(
                    40901,
                    "clientMessageId 已被其他消息使用"
            );
        }
    }

    /**
     * 买家查询会话消息。
     */
    public ChatMessagePageResponse getBuyerMessages(
            Long buyerUserId,
            Long conversationId,
            ChatMessageCursorQuery query
    ) {
        SysUser buyer = requireActiveUser(buyerUserId);
        ChatConversation conversation = requireBuyerConversation(conversationId,buyerUserId);
        Merchant merchant = requireActiveMerchantById(conversation.getMerchantId());
        return queryMessages(conversation, buyer, merchant, query);
    }

    /**
     * 商家查询会话消息。
     */
    public ChatMessagePageResponse getMerchantMessages(
            Long merchantUserId,
            Long conversationId,
            ChatMessageCursorQuery query
    ) {
        Merchant merchant = requireActiveMerchantByUserId(merchantUserId);
        ChatConversation conversation = requireMerchantConversation(conversationId,merchant.getId());
        SysUser buyer = requireActiveUser(conversation.getBuyerUserId());
        return queryMessages(conversation, buyer, merchant, query);
    }

    /**
     * 按游标方向查询消息。
     *
     * <p>数据库多查一条，用于判断 hasMore。
     * 最终始终按消息 ID 升序返回。</p>
     */
    private ChatMessagePageResponse queryMessages(
            ChatConversation conversation,
            SysUser buyer,
            Merchant merchant,
            ChatMessageCursorQuery query
    ) {
        int limit = query.effectiveLimit();
        int databaseLimit = limit + 1;

        List<ChatMessage> queried;
        boolean databaseReturnedDescending;

        if (query.beforeMessageId() != null) {
            queried = messageMapper.selectBeforeMessage(
                    conversation.getId(),
                    query.beforeMessageId(),
                    databaseLimit
            );
            databaseReturnedDescending = true;
        } else if (query.afterMessageId() != null) {
            queried = messageMapper.selectAfterMessage(
                    conversation.getId(),
                    query.afterMessageId(),
                    databaseLimit
            );
            databaseReturnedDescending = false;
        } else {
            queried = messageMapper.selectLatestMessages(
                    conversation.getId(),
                    databaseLimit
            );
            databaseReturnedDescending = true;
        }

        boolean hasMore = queried.size() > limit;

        List<ChatMessage> page = new ArrayList<>(hasMore ? queried.subList(0, limit) : queried);

        if (databaseReturnedDescending) {
            Collections.reverse(page);
        }

        List<ChatMessageResponse> response = page.stream().map(message -> toMessageResponse(
                message,
                conversation,
                buyer,
                merchant
        )).toList();
        return ChatMessagePageResponse.of(response, hasMore);
    }

    private ChatConversation requireBuyerConversation(Long conversationId,Long buyerUserId) {
        ChatConversation conversation = conversationMapper.selectById(conversationId);

        if (conversation == null || !Objects.equals(conversation.getBuyerUserId(),buyerUserId)) {
            throw new BusinessException(40401, "聊天会话不存在或无权访问");
        }
        return conversation;
    }

    private ChatConversation requireMerchantConversation(Long conversationId,Long merchantUserId) {
        ChatConversation conversation = conversationMapper.selectById(conversationId);

        if (conversation == null || !Objects.equals(conversation.getMerchantId(),merchantUserId)) {
            throw new BusinessException(40401, "聊天会话不存在或无权访问");
        }
        return conversation;
    }

    private SysUser requireActiveUser(Long userId) {
        SysUser user = sysUserMapper.selectById(userId);

        if (user == null || !Integer.valueOf(1).equals(user.getStatus())) {
            throw new BusinessException(40401, "用户不存在或已禁用");
        }
        return user;
    }

    private Merchant requireActiveMerchantById(Long merchantId) {
        Merchant merchant = merchantMapper.selectById(merchantId);

        if (merchant == null || !Integer.valueOf(1).equals(merchant.getStatus())) {
            throw new BusinessException(40401, "商户不存在或已禁用");
        }

        // 商户资料可用时，还要确保它所关联的登录账号没有被禁用。
        requireActiveUser(merchant.getUserId());
        return merchant;
    }

    private Merchant requireActiveMerchantByUserId(Long userId) {
        Merchant merchant = merchantMapper.selectOne(
                new LambdaQueryWrapper<Merchant>()
                        .eq(Merchant::getUserId,userId)
                        .last("LIMIT 1")
        );

        if (merchant == null || !Integer.valueOf(1).equals(merchant.getStatus())) {
            throw new BusinessException(40401, "商户不存在或已禁用");
        }
        requireActiveUser(userId);
        return merchant;
    }

    /**
     * 将消息实体转换为对外响应。
     * TEXT 返回 content，ORDER 返回 orderCard，其他载荷保持 null。
     */
    private ChatMessageResponse toMessageResponse(
            ChatMessage message,
            ChatConversation conversation,
            SysUser buyer,
            Merchant merchant
    ) {
        ChatSenderResponse sender;

        if (BUYER_ROLE.equals(message.getSenderRole())) {
            if (!Objects.equals(message.getSenderUserId(),buyer.getId())) {
                throw new BusinessException(50000, "聊天消息买家身份数据异常");
            }
            sender = new ChatSenderResponse(
                    buyer.getId(),
                    BUYER_ROLE,
                    null,
                    buyerDisplayName(buyer),
                    buyer.getAvatarUrl()
            );
        } else if (MERCHANT_ROLE.equals(message.getSenderRole())) {
            if (!Objects.equals(message.getSenderUserId(),merchant.getUserId())) {
                throw new BusinessException(50000, "聊天消息商户身份数据异常");
            }
            sender = merchantSender(merchant);
        } else {
            throw new BusinessException(50000, "聊天消息发送角色异常");
        }

        String content = null;
        ChatImageResponse image = null;
        ChatOrderCardResponse orderCard = null;

        if (message.getMessageType() == ChatMessageType.TEXT) {
            // TEXT 只返回纯文字。
            content = message.getTextContent();

        } else if (message.getMessageType() == ChatMessageType.IMAGE) {
            /*
             * IMAGE 根据数据库中的私有 objectKey 生成短期签名 URL。
             * 不能直接把 objectKey 当作公开 URL 返回。
             */
            image = chatImageStorageService.buildResponse(
                    conversation.getId(),
                    message.getImageObjectKey(),
                    message.getImageMetaJson()
            );

        } else if (message.getMessageType() == ChatMessageType.ORDER) {
            // ORDER 返回服务端组装的脱敏订单卡片。
            orderCard = toOrderCardResponse(
                    message,
                    conversation
            );

        } else {
            throw new BusinessException(
                    50000,
                    "无法处理的聊天消息类型"
            );
        }

        return new ChatMessageResponse(
                message.getId(),
                conversation.getId(),
                message.getClientMessageId(),
                message.getMessageType(),
                sender,
                content,
                image,
                orderCard,
                message.getCreatedAt()
        );
    }

    private ChatConversationResponse toBuyerConversationResponse(
            ChatConversation conversation,
            SysUser buyer,
            Merchant merchant
    ) {
        ChatMessageResponse lastMessage = null;

        if (conversation.getLastMessageId() != null) {
            ChatMessage message = messageMapper.selectInConversation(
                    conversation.getId(),
                    conversation.getLastMessageId()
            );

            if (message == null) {
                throw new BusinessException(
                        50000,
                        "聊天会话最后消息数据异常"
                );
            }

            lastMessage = toMessageResponse(
                    message,
                    conversation,
                    buyer,
                    merchant
            );
        }

        long unreadCount = messageMapper.countUnreadMessages(
                conversation.getId(),
                MERCHANT_ROLE,
                conversation.getBuyerLastReadMessageId()
        );

        return new ChatConversationResponse(
                conversation.getId(),
                merchantSender(merchant),
                lastMessage,
                unreadCount,
                conversation.getBuyerLastReadMessageId(),
                conversation.getCreatedAt()
        );
    }

    private ChatSenderResponse merchantSender(Merchant merchant) {
        String displayName = StringUtils.hasText(merchant.getName())
                ? merchant.getName()
                : "商家";

        return new ChatSenderResponse(
                merchant.getUserId(),
                MERCHANT_ROLE,
                merchant.getId(),
                displayName,
                merchant.getLogoUrl()
        );
    }

    private String buyerDisplayName(SysUser buyer) {
        if (StringUtils.hasText(buyer.getNickname())) {
            return buyer.getNickname();
        }

        // 不使用手机号兜底，避免在聊天响应中泄露账号隐私。
        return "用户" + buyer.getId();
    }

    /**
     * 构造商家视角的会话摘要。
     *
     * <p>商家看到的 peer 是买家，未读数量只统计 USER 发送的消息。</p>
     */
    private ChatConversationResponse toMerchantConversationResponse(
            ChatConversation conversation,
            SysUser buyer,
            Merchant merchant
    ) {
        ChatMessageResponse lastMessage = null;

        if (conversation.getLastMessageId() != null) {
            ChatMessage message = messageMapper.selectInConversation(
                    conversation.getId(),
                    conversation.getLastMessageId()
            );

            if (message == null) {
                throw new BusinessException(
                        50000,
                        "聊天会话最后消息数据异常"
                );
            }
            lastMessage = toMessageResponse(
                    message,
                    conversation,
                    buyer,
                    merchant
            );
        }
        long unreadCount = messageMapper.countUnreadMessages(
                conversation.getId(),
                BUYER_ROLE,
                conversation.getMerchantLastReadMessageId()
        );

        ChatSenderResponse buyerPeer = new ChatSenderResponse(
                buyer.getId(),
                BUYER_ROLE,
                null,
                buyerDisplayName(buyer),
                buyer.getAvatarUrl()
        );

        return new ChatConversationResponse(
                conversation.getId(),
                buyerPeer,
                lastMessage,
                unreadCount,
                conversation.getMerchantLastReadMessageId(),
                conversation.getCreatedAt()
        );
    }

    /**
     * 分页查询买家的会话列表。
     */
    public PageResult<ChatConversationResponse> listBuyerConversations(
            Long buyerUserId,
            long page,
            long pageSize
    ) {
        SysUser buyer = requireActiveUser(buyerUserId);

        Page<ChatConversation> conversationPage = conversationMapper.selectPage(
                new Page<>(page,pageSize),
                new LambdaQueryWrapper<ChatConversation>()
                        .eq(ChatConversation::getBuyerUserId, buyerUserId)
                        /*
                         * 有消息的会话排在前面，并按最后消息时间倒序。
                         * 空会话的 lastMessageAt 为 null，会自然排在后面。
                         */
                        .orderByDesc(ChatConversation::getLastMessageAt)
                        .orderByDesc(ChatConversation::getId)
        );

        List<ChatConversationResponse> records = conversationPage.getRecords()
                .stream()
                .map(conversation -> {
                    Merchant merchant = requireActiveMerchantById(conversation.getMerchantId());

                    return toBuyerConversationResponse(
                            conversation,
                            buyer,
                            merchant
                    );
                }).toList();

        return new PageResult<>(
                records,
                conversationPage.getCurrent(),
                conversationPage.getSize(),
                conversationPage.getTotal()
        );
    }

    /**
     * 分页查询商家的会话列表。
     */
    public PageResult<ChatConversationResponse> listMerchantConversations(
            Long merchantUserId,
            long page,
            long pageSize
    ) {
        Merchant merchant = requireActiveMerchantByUserId(
                merchantUserId
        );

        Page<ChatConversation> conversationPage =
                conversationMapper.selectPage(
                        new Page<>(page, pageSize),
                        new LambdaQueryWrapper<ChatConversation>()
                                .eq(
                                        ChatConversation::getMerchantId,
                                        merchant.getId()
                                )
                                .orderByDesc(
                                        ChatConversation::getLastMessageAt
                                )
                                .orderByDesc(ChatConversation::getId)
                );

        List<ChatConversationResponse> records =
                conversationPage.getRecords()
                        .stream()
                        .map(conversation -> {
                            SysUser buyer = requireActiveUser(
                                    conversation.getBuyerUserId()
                            );

                            return toMerchantConversationResponse(
                                    conversation,
                                    buyer,
                                    merchant
                            );
                        })
                        .toList();

        return new PageResult<>(
                records,
                conversationPage.getCurrent(),
                conversationPage.getSize(),
                conversationPage.getTotal()
        );
    }

    /**
     * 买家推进已读游标。
     */
    @Transactional
    public ChatReadReceiptResponse markBuyerRead(
            Long buyerUserId,
            Long conversationId,
            MarkChatReadRequest request
    ) {
        SysUser buyer = requireActiveUser(buyerUserId);

        ChatConversation conversation =
                conversationMapper.selectBuyerConversationForUpdate(
                        conversationId,
                        buyerUserId
                );

        if (conversation == null) {
            throw new BusinessException(
                    40401,
                    "聊天会话不存在或无权访问"
            );
        }

        ChatMessage targetMessage = messageMapper.selectInConversation(
                conversationId,
                request.lastReadMessageId()
        );

        if (targetMessage == null) {
            throw new BusinessException(
                    40001,
                    "最后已读消息不属于当前会话"
            );
        }
        Merchant merchant = requireActiveMerchantById(
                conversation.getMerchantId()
        );

        Long currentCursor = conversation.getBuyerLastReadMessageId();
        /*
         * 重复请求或旧请求直接返回当前游标。
         * 不更新数据库，也不重复推送 MESSAGE_READ。
         */

        if (currentCursor != null
                && currentCursor >= request.lastReadMessageId()) {
            return new ChatReadReceiptResponse(
                    BUYER_ROLE,
                    currentCursor,
                    LocalDateTime.now()
                            .truncatedTo(ChronoUnit.MILLIS)
            );
        }

        int updated = conversationMapper.advanceBuyerReadCursor(
                conversationId,
                buyerUserId,
                request.lastReadMessageId()
        );

        if (updated != 1) {
            throw new BusinessException(
                    50000,
                    "更新买家已读位置失败"
            );
        }

        conversation.setBuyerLastReadMessageId(request.lastReadMessageId());

        ChatReadReceiptResponse receipt =
                new ChatReadReceiptResponse(
                        BUYER_ROLE,
                        request.lastReadMessageId(),
                        LocalDateTime.now()
                                .truncatedTo(ChronoUnit.MILLIS)
                );

        eventPublisher.publishEvent(
                new ChatReadAdvancedEvent(
                        buyer.getId(),
                        merchant.getId(),
                        receipt,
                        toBuyerConversationResponse(
                                conversation,
                                buyer,
                                merchant
                        ),
                        toMerchantConversationResponse(
                                conversation,
                                buyer,
                                merchant
                        )
                )
        );

        return receipt;
    }

    /**
     * 商家推进已读游标。
     */
    @Transactional
    public ChatReadReceiptResponse markMerchantRead(
            Long merchantUserId,
            Long conversationId,
            MarkChatReadRequest request
    ) {
        SysUser merchantUser = requireActiveUser(merchantUserId);
        Merchant merchant = requireActiveMerchantByUserId(
                merchantUserId
        );

        ChatConversation conversation =
                conversationMapper.selectMerchantConversationForUpdate(
                        conversationId,
                        merchant.getId()
                );

        if (conversation == null) {
            throw new BusinessException(
                    40401,
                    "聊天会话不存在或无权访问"
            );
        }

        ChatMessage targetMessage = messageMapper.selectInConversation(
                conversationId,
                request.lastReadMessageId()
        );

        if (targetMessage == null) {
            throw new BusinessException(
                    40001,
                    "最后已读消息不属于当前会话"
            );
        }

        Long currentCursor =
                conversation.getMerchantLastReadMessageId();

        if (currentCursor != null
                && currentCursor >= request.lastReadMessageId()) {
            return new ChatReadReceiptResponse(
                    MERCHANT_ROLE,
                    currentCursor,
                    LocalDateTime.now()
                            .truncatedTo(ChronoUnit.MILLIS)
            );
        }

        int updated = conversationMapper.advanceMerchantReadCursor(
                conversationId,
                merchant.getId(),
                request.lastReadMessageId()
        );

        if (updated != 1) {
            throw new BusinessException(
                    50000,
                    "更新商家已读位置失败"
            );
        }

        conversation.setMerchantLastReadMessageId(
                request.lastReadMessageId()
        );

        SysUser buyer = requireActiveUser(
                conversation.getBuyerUserId()
        );

        ChatReadReceiptResponse receipt =
                new ChatReadReceiptResponse(
                        MERCHANT_ROLE,
                        request.lastReadMessageId(),
                        LocalDateTime.now()
                                .truncatedTo(ChronoUnit.MILLIS)
                );

        eventPublisher.publishEvent(
                new ChatReadAdvancedEvent(
                        buyer.getId(),
                        merchant.getId(),
                        receipt,
                        toBuyerConversationResponse(
                                conversation,
                                buyer,
                                merchant
                        ),
                        toMerchantConversationResponse(
                                conversation,
                                buyer,
                                merchant
                        )
                )
        );
        return receipt;
    }

    /**
     * 经过校验和标准化后的消息内容。
     *
     * TEXT 消息：
     * - textContent 有值
     * - order 为 null
     *
     * ORDER 消息：
     * - textContent 为 null
     * - order 有值
     */
    private record PreparedMessage(
            ChatMessageType type,
            String textContent,
            MallOrder order
    ) {
    }

    /**
     * 根据消息类型检查请求，并生成可以写入数据库的数据。
     */
    private PreparedMessage prepareMessage(
            SendChatMessageRequest request,
            ChatConversation conversation
    ) {
        if (request.type() == ChatMessageType.TEXT) {
            String content = request.content() == null
                    ? null
                    : request.content().trim();

            if (content == null || content.isBlank()) {
                throw new BusinessException(40001, "文字消息内容不能为空");
            }

            return new PreparedMessage(
                    ChatMessageType.TEXT,
                    content,
                    null
            );
        }

        if (request.type() == ChatMessageType.ORDER) {
            String orderNo = request.orderNo() == null
                    ? null
                    : request.orderNo().trim();

            if (orderNo == null || orderNo.isBlank()) {
                throw new BusinessException(40001, "订单号不能为空");
            }

            /*
             * 查询条件同时包含买家和商家。
             * 即使前端伪造订单号，也不能发送不属于当前会话的订单。
             */
            MallOrder order = mallOrderMapper.selectChatOrder(
                    orderNo,
                    conversation.getBuyerUserId(),
                    conversation.getMerchantId()
            );

            if (order == null) {
                throw new BusinessException(
                        40401,
                        "订单不存在，或订单不属于当前聊天双方"
                );
            }

            return new PreparedMessage(
                    ChatMessageType.ORDER,
                    null,
                    order
            );
        }

        /*
         * IMAGE 暂时还没有实现。
         * 即使前端绕过 DTO 校验，也必须在业务层再次阻止。
         */
        throw new BusinessException(40001, "暂不支持该消息类型");
    }

    /**
     * 根据消息中的 order_id 构建订单卡片。
     *
     * 订单金额和状态读取 mall_order 当前数据；
     * 商品标题和图片读取 order_item 下单时的快照。
     *
     * 因此：
     * - 商品后来改名不会影响订单快照；
     * - 订单状态改变后，再查询消息可以看到最新状态。
     */
    private ChatOrderCardResponse toOrderCardResponse(
            ChatMessage message,
            ChatConversation conversation
    ) {
        if (message.getOrderId() == null) {
            throw new BusinessException(50000, "订单消息缺少 orderId");
        }

        MallOrder order = mallOrderMapper.selectById(message.getOrderId());

        if (order == null) {
            throw new BusinessException(50000, "订单消息关联的订单不存在");
        }

        /*
         * 防御性检查：即使数据库数据被人工改坏，也不能把其他买家或其他
         * 商家的订单信息通过聊天历史接口泄露出去。
         */
        if (!Objects.equals(order.getUserId(), conversation.getBuyerUserId())
                || !Objects.equals(order.getMerchantId(), conversation.getMerchantId())) {
            throw new BusinessException(50000, "订单消息与聊天会话归属不一致");
        }

        List<OrderItem> items =
                orderItemMapper.selectByOrderId(order.getId());

        if (items == null || items.isEmpty()) {
            throw new BusinessException(50000, "订单中不存在商品明细");
        }

        /*
         * 订单卡片只展示一个代表商品。
         * 如果订单中有多个商品，使用第一条订单商品快照。
         */
        OrderItem firstItem = items.get(0);

        return new ChatOrderCardResponse(
                order.getOrderNo(),
                firstItem.getProductTitle(),
                firstItem.getProductImage(),
                items.size(),
                order.getPayAmountCent(),
                order.getStatus()
        );
    }
}
