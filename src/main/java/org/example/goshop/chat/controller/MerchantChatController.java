package org.example.goshop.chat.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.example.goshop.chat.dto.ChatMessageCursorQuery;
import org.example.goshop.chat.dto.ChatMessagePageResponse;
import org.example.goshop.chat.dto.ChatMessageResponse;
import org.example.goshop.chat.dto.SendChatMessageRequest;
import org.example.goshop.chat.service.ChatService;
import org.example.goshop.common.api.Result;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.example.goshop.chat.dto.ChatConversationResponse;
import org.example.goshop.chat.dto.ChatReadReceiptResponse;
import org.example.goshop.chat.dto.MarkChatReadRequest;
import org.example.goshop.product.dto.PageResult;
import org.example.goshop.chat.dto.ChatImageMessageRequest;
import org.springframework.http.MediaType;

/**
 * 商家聊天接口。
 *
 * <p>商家不能主动创建会话，只能查询和回复买家已经创建的会话。</p>
 */
@Tag(name = "商家聊天")
@RestController
@RequestMapping("/api/v1/merchant/chat")
@RequiredArgsConstructor
@Validated
public class MerchantChatController {

    private final ChatService chatService;

    @Operation(summary = "发送文字或订单卡片消息")
    @PostMapping("/conversations/{conversationId}/messages")
    public Result<ChatMessageResponse> sendMessage(
            Authentication authentication,

            @PathVariable
            @Positive(message = "会话 ID 必须是正数")
            Long conversationId,

            @Valid @RequestBody SendChatMessageRequest request
    ) {
        // 当前商家账号 ID 来自已经通过 JWT 校验的 Authentication，不能由前端传入。
        Long merchantUserId = (Long) authentication.getPrincipal();

        return Result.ok(
                chatService.sendMerchantMessage(
                        merchantUserId,
                        conversationId,
                        request
                )
        );
    }

    /**
     * 商家上传一张图片并创建 IMAGE 消息。
     */
    @Operation(summary = "商家发送图片消息")
    @PostMapping(
            value = "/conversations/{conversationId}/images",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public Result<ChatMessageResponse> sendImage(
            Authentication authentication,

            @PathVariable
            @Positive(message = "会话 ID 必须是正数")
            Long conversationId,

            @Valid
            @ModelAttribute
            ChatImageMessageRequest request
    ) {
        Long merchantUserId =
                (Long) authentication.getPrincipal();

        return Result.ok(
                chatService.sendMerchantImageMessage(
                        merchantUserId,
                        conversationId,
                        request
                )
        );
    }

    @GetMapping("/conversations/{conversationId}/messages")
    @Operation(summary = "商家查询会话历史消息")
    public Result<ChatMessagePageResponse> messages(
            Authentication authentication,

            @PathVariable
            @Positive(message = "会话 ID 必须是正数")
            Long conversationId,

            @Valid
            @ModelAttribute
            ChatMessageCursorQuery query
    ) {
        Long merchantUserId = (Long) authentication.getPrincipal();

        return Result.ok(
                chatService.getMerchantMessages(
                        merchantUserId,
                        conversationId,
                        query
                )
        );
    }

    @GetMapping("/conversations")
    @Operation(summary = "分页查询商家会话列表")
    public Result<PageResult<ChatConversationResponse>> conversations(
            Authentication authentication,

            @RequestParam(defaultValue = "1")
            @Min(value = 1, message = "页码不能小于 1")
            long page,

            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "每页数量不能小于 1")
            @Max(value = 50, message = "每页数量不能超过 50")
            long pageSize
    ) {
        Long merchantUserId = (Long) authentication.getPrincipal();

        return Result.ok(
                chatService.listMerchantConversations(
                        merchantUserId,
                        page,
                        pageSize
                )
        );
    }

    @PutMapping("/conversations/{conversationId}/read")
    @Operation(summary = "商家推进会话已读位置")
    public Result<ChatReadReceiptResponse> markRead(
            Authentication authentication,

            @PathVariable
            @Positive(message = "会话 ID 必须是正数")
            Long conversationId,

            @Valid
            @RequestBody
            MarkChatReadRequest request
    ) {
        Long merchantUserId = (Long) authentication.getPrincipal();

        return Result.ok(
                chatService.markMerchantRead(
                        merchantUserId,
                        conversationId,
                        request
                )
        );
    }
}
