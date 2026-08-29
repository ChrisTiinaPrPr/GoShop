package org.example.goshop.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 联系商家模块的统一可调配置。
 *
 * <p>所有值通过 {@code goshop.chat} 前缀绑定，并在 {@code application.yml} 中映射到环境变量。
 * 构造阶段执行安全边界校验，使错误配置在应用启动时直接失败，而不是等到用户上传图片或
 * 建立 WebSocket 连接时才暴露。</p>
 *
 * @param allowedOrigins       允许发起聊天 WebSocket 握手的买家端和商家端 Origin
 * @param imageMaxSizeMb       单张聊天图片大小上限，单位 MB
 * @param messageRatePerMinute 单账号每分钟允许发送的聊天消息数量
 */
@ConfigurationProperties(prefix = "goshop.chat")
public record ChatProperties(
        List<String> allowedOrigins,
        int imageMaxSizeMb,
        int messageRatePerMinute
) {

    private static final int MAX_CONFIGURABLE_IMAGE_SIZE_MB = 5;
    private static final int MAX_CONFIGURABLE_MESSAGE_RATE = 1000;
    private static final long BYTES_PER_MEGABYTE = 1024L * 1024L;

    public ChatProperties {
        // 环境变量通常使用逗号分隔多个 Origin；绑定后统一去空白、去重并转为不可变列表。
        allowedOrigins = allowedOrigins == null
                ? List.of()
                : allowedOrigins.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();

        if (allowedOrigins.isEmpty()) {
            throw new IllegalArgumentException("聊天 WebSocket Origin 白名单不能为空");
        }
        if (allowedOrigins.contains("*")) {
            throw new IllegalArgumentException("聊天 WebSocket 不允许使用通配符 Origin");
        }
        if (imageMaxSizeMb < 1 || imageMaxSizeMb > MAX_CONFIGURABLE_IMAGE_SIZE_MB) {
            throw new IllegalArgumentException("聊天图片大小上限必须在 1～5 MB 之间");
        }
        if (messageRatePerMinute < 1
                || messageRatePerMinute > MAX_CONFIGURABLE_MESSAGE_RATE) {
            throw new IllegalArgumentException("聊天消息频率上限必须在 1～1000 条/分钟之间");
        }
    }

    /** Spring WebSocket 注册 API 使用数组，这里集中完成不可变 List 到数组的转换。 */
    public String[] allowedOriginsArray() {
        return allowedOrigins.toArray(String[]::new);
    }

    /**
     * 图片校验和 OSS 上传逻辑使用字节数，统一在配置对象中转换，避免业务代码重复换算。
     */
    public long imageMaxSizeBytes() {
        return imageMaxSizeMb * BYTES_PER_MEGABYTE;
    }
}
