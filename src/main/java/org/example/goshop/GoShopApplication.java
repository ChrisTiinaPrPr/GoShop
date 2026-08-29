package org.example.goshop;

import org.example.goshop.security.JwtProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@MapperScan({
        // 扫描项目原有的各业务 mapper 包。
        "org.example.goshop.**.mapper",
        // MQ Outbox Mapper 与实体、发布服务放在同一个 outbox 包中，
        // 不匹配上面的 **.mapper，因此必须显式加入扫描范围。
        "org.example.goshop.infrastructure.mq.outbox",
        // MQ 消费幂等日志和支付通知 Mapper 放在 consumer 包中，
        // 它们同样不在名为 mapper 的包下，必须显式扫描才能生成 Spring Bean。
        "org.example.goshop.infrastructure.mq.consumer"
})
@EnableConfigurationProperties(JwtProperties.class)
public class GoShopApplication {

    public static void main(String[] args) {
        SpringApplication.run(GoShopApplication.class, args);
    }

}
