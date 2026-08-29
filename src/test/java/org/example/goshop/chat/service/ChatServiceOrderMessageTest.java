package org.example.goshop.chat.service;

import org.example.goshop.auth.entity.SysUser;
import org.example.goshop.auth.mapper.SysUserMapper;
import org.example.goshop.chat.dto.ChatMessageResponse;
import org.example.goshop.chat.dto.ChatMessageType;
import org.example.goshop.chat.dto.SendChatMessageRequest;
import org.example.goshop.chat.entity.ChatConversation;
import org.example.goshop.chat.entity.ChatMessage;
import org.example.goshop.chat.event.ChatMessageCreatedEvent;
import org.example.goshop.chat.mapper.ChatConversationMapper;
import org.example.goshop.chat.mapper.ChatMessageMapper;
import org.example.goshop.merchant.entity.Merchant;
import org.example.goshop.merchant.mapper.MerchantMapper;
import org.example.goshop.order.entity.MallOrder;
import org.example.goshop.order.entity.OrderItem;
import org.example.goshop.order.mapper.MallOrderMapper;
import org.example.goshop.order.mapper.OrderItemMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证订单卡片消息从权限校验、落库到响应组装的核心链路。 */
@ExtendWith(MockitoExtension.class)
class ChatServiceOrderMessageTest {

    @Mock
    private ChatConversationMapper conversationMapper;
    @Mock
    private ChatMessageMapper messageMapper;
    @Mock
    private MerchantMapper merchantMapper;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private MallOrderMapper mallOrderMapper;
    @Mock
    private OrderItemMapper orderItemMapper;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private ChatImageStorageService chatImageStorageService;

    @InjectMocks
    private ChatService chatService;

    @Test
    void buyerShouldSaveOrderIdAndReturnOrderCard() {
        long buyerUserId = 101L;
        long merchantUserId = 202L;
        long merchantId = 301L;
        long conversationId = 11L;
        long orderId = 401L;
        long messageId = 501L;

        SysUser buyer = new SysUser();
        buyer.setId(buyerUserId);
        buyer.setNickname("测试买家");
        buyer.setStatus(1);

        SysUser merchantUser = new SysUser();
        merchantUser.setId(merchantUserId);
        merchantUser.setStatus(1);

        Merchant merchant = new Merchant();
        merchant.setId(merchantId);
        merchant.setUserId(merchantUserId);
        merchant.setName("测试店铺");
        merchant.setStatus(1);

        ChatConversation conversation = new ChatConversation();
        conversation.setId(conversationId);
        conversation.setBuyerUserId(buyerUserId);
        conversation.setMerchantId(merchantId);
        conversation.setCreatedAt(LocalDateTime.now());

        MallOrder order = new MallOrder();
        order.setId(orderId);
        order.setOrderNo("ORDER-20260806-001");
        order.setUserId(buyerUserId);
        order.setMerchantId(merchantId);
        order.setPayAmountCent(19900L);
        order.setStatus("WAITING_SHIPMENT");

        OrderItem orderItem = new OrderItem();
        orderItem.setOrderId(orderId);
        orderItem.setProductTitle("测试商品");
        orderItem.setProductImage("https://example.com/product.png");

        when(sysUserMapper.selectById(buyerUserId)).thenReturn(buyer);
        when(sysUserMapper.selectById(merchantUserId)).thenReturn(merchantUser);
        when(merchantMapper.selectById(merchantId)).thenReturn(merchant);
        when(conversationMapper.selectBuyerConversationForUpdate(
                conversationId,
                buyerUserId
        )).thenReturn(conversation);
        when(mallOrderMapper.selectChatOrder(
                order.getOrderNo(),
                buyerUserId,
                merchantId
        )).thenReturn(order);
        when(mallOrderMapper.selectById(orderId)).thenReturn(order);
        when(orderItemMapper.selectByOrderId(orderId)).thenReturn(List.of(orderItem));

        /*
         * MyBatis-Plus 在真实运行时会为实体生成消息 ID。
         * Mapper 在本测试中是 Mock，所以这里模拟这一行为。
         */
        AtomicReference<ChatMessage> savedMessage = new AtomicReference<>();
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(invocation -> {
            ChatMessage message = invocation.getArgument(0);
            message.setId(messageId);
            savedMessage.set(message);
            return 1;
        });
        when(conversationMapper.advanceLastMessage(
                org.mockito.ArgumentMatchers.eq(conversationId),
                org.mockito.ArgumentMatchers.eq(messageId),
                any(LocalDateTime.class)
        )).thenReturn(1);
        when(messageMapper.selectInConversation(conversationId, messageId))
                .thenAnswer(invocation -> savedMessage.get());

        SendChatMessageRequest request = new SendChatMessageRequest(
                "eb543b0e-8222-4f96-b383-381ea9f42d77",
                ChatMessageType.ORDER,
                null,
                order.getOrderNo()
        );

        ChatMessageResponse response = chatService.sendBuyerMessage(
                buyerUserId,
                conversationId,
                request
        );

        ArgumentCaptor<ChatMessage> messageCaptor =
                ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageMapper).insert(messageCaptor.capture());

        ChatMessage insertedMessage = messageCaptor.getValue();
        assertEquals(ChatMessageType.ORDER, insertedMessage.getMessageType());
        assertEquals(orderId, insertedMessage.getOrderId());
        assertNull(insertedMessage.getTextContent());

        assertEquals(ChatMessageType.ORDER, response.type());
        assertNull(response.content());
        assertNull(response.image());
        assertNotNull(response.orderCard());
        assertEquals(order.getOrderNo(), response.orderCard().orderNo());
        assertEquals("测试商品", response.orderCard().productTitle());
        assertEquals(1, response.orderCard().productTypeCount());
        assertEquals(19900L, response.orderCard().payAmountCent());
        assertEquals("WAITING_SHIPMENT", response.orderCard().status());

        // 数据库事务提交后，监听器会将这条事件推送给聊天双方。
        verify(eventPublisher).publishEvent(any(ChatMessageCreatedEvent.class));
    }
}
