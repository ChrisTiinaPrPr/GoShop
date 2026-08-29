package org.example.goshop.chat.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.util.StringUtils;

/**
 * 通过 JSON REST 接口发送文字或订单消息的统一请求。
 *
 * <p>IMAGE 消息必须使用 multipart 图片接口，避免客户端先上传对象再伪造 objectKey。
 * {@link #isPayloadValid()} 负责表达普通字段注解无法覆盖的消息类型与载荷互斥关系。</p>
 */
@Schema(
        name = "SendChatMessageRequest",
        description = "发送文字或订单消息。TEXT 只传 content；ORDER 只传 orderNo；IMAGE 不允许使用此接口"
)
public record SendChatMessageRequest(
        @Schema(
                description = "客户端生成的 UUID 幂等键；网络重试必须复用同一个值",
                example = "0ec5b9b4-2b87-4be7-9da4-a699cb8cc1ad",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank(message = "客户端消息 ID 不能为空")
        @Pattern(
                regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
                message = "客户端消息 ID 必须是标准 UUID"
        )
        String clientMessageId,

        @Schema(
                description = "JSON 发送接口只接受 TEXT 或 ORDER",
                example = "TEXT",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotNull(message = "消息类型不能为空")
        ChatMessageType type,

        @Schema(
                description = "纯文本正文；type=TEXT 时必填，去除首尾空白后不能为空，最多 2000 字符",
                example = "请问今天可以发货吗？",
                maxLength = 2000,
                requiredMode = Schema.RequiredMode.NOT_REQUIRED
        )
        @Size(max = 2000, message = "消息正文不能超过 2000 个字符")
        String content,

        @Schema(
                description = "订单号；type=ORDER 时必填，服务端会校验订单同时属于当前买家和会话商家",
                example = "2041286378101014528",
                maxLength = 32,
                requiredMode = Schema.RequiredMode.NOT_REQUIRED
        )
        @Size(max = 32, message = "订单号不能超过 32 个字符")
        String orderNo
) {

    /** 保证不同消息类型的载荷字段严格互斥，防止多余字段进入业务层。 */
    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "TEXT 只能携带正文，ORDER 只能携带订单号，图片必须使用图片消息接口")
    public boolean isPayloadValid() {
        if (type == null) {
            // type 为空交给 @NotNull 返回更精确的字段错误。
            return true;
        }
        return switch (type) {
            case TEXT -> StringUtils.hasText(content) && orderNo == null;
            case ORDER -> content == null && StringUtils.hasText(orderNo);
            case IMAGE -> false;
        };
    }
}
