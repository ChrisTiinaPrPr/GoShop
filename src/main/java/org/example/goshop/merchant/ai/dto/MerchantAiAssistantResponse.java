package org.example.goshop.merchant.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.example.goshop.merchant.ai.entity.MerchantAiAssistant;
import org.example.goshop.merchant.dto.MerchantProfileResponse;

import java.time.LocalDateTime;

/**
 * 商家看到的智能导购助手配置。
 *
 * <p>尚未创建数据库配置时也返回可展示的默认预览，此时 configured=false、
 * id 和 updatedAt 为空、enabled=false。GET 接口不会为了生成默认预览
 * 产生数据库写入。</p>
 */
@Schema(
        name = "MerchantAiAssistantResponse",
        description = "当前商家的智能导购助手配置或默认预览"
)
public record MerchantAiAssistantResponse(
        @Schema(description = "助手 ID；尚未配置时为空")
        Long id,

        @Schema(description = "所属商家 ID")
        Long merchantId,

        @Schema(description = "助手名称")
        String name,

        @Schema(description = "助手头像 URL")
        String avatarUrl,

        @Schema(description = "欢迎语")
        String welcomeMessage,

        @Schema(description = "是否允许买家使用")
        boolean enabled,

        @Schema(description = "是否已经保存过助手配置")
        boolean configured,

        @Schema(description = "最近配置更新时间；尚未配置时为空")
        LocalDateTime updatedAt
) {

    /**
     * 将已持久化配置转换成响应。
     *
     * <p>助手没有自定义头像时使用店铺 Logo，避免管理页和后续买家页
     * 出现空头像。</p>
     */
    public static MerchantAiAssistantResponse configured(
            MerchantAiAssistant assistant,
            MerchantProfileResponse merchant
    ) {
        String effectiveAvatar =
                assistant.getAvatarUrl() == null
                        || assistant.getAvatarUrl().isBlank()
                        ? merchant.logoUrl()
                        : assistant.getAvatarUrl();

        return new MerchantAiAssistantResponse(
                assistant.getId(),
                merchant.id(),
                assistant.getName(),
                effectiveAvatar,
                assistant.getWelcomeMessage(),
                Integer.valueOf(1).equals(
                        assistant.getEnabled()
                ),
                true,
                assistant.getUpdatedAt()
        );
    }

    /**
     * 创建没有数据库副作用的默认预览。
     */
    public static MerchantAiAssistantResponse preview(
            MerchantProfileResponse merchant
    ) {
        String assistantName =
                merchant.name() + "智能导购";

        return new MerchantAiAssistantResponse(
                null,
                merchant.id(),
                assistantName,
                merchant.logoUrl(),
                "您好，我是" + assistantName
                        + "，可以为您介绍本店商品。",
                false,
                false,
                null
        );
    }
}
