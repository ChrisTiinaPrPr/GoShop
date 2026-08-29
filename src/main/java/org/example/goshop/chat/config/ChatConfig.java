package org.example.goshop.chat.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 注册聊天模块配置对象。
 */
@Configuration
@EnableConfigurationProperties({
        ChatProperties.class,
        ChatOssProperties.class
})
public class ChatConfig {
}
