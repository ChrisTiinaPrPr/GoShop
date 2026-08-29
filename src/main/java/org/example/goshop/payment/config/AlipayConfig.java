package org.example.goshop.payment.config;

import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties({
        AlipayProperties.class,
})
public class AlipayConfig {

    private final AlipayProperties properties;

    @Bean
    public AlipayClient alipayClient() {
        return new DefaultAlipayClient(
                properties.getGateway(),
                properties.getAppId(),
                properties.getPrivateKey(),
                "json",
                "UTF-8",
                properties.getPublicKey(),
                "RSA2"
        );
    }

}
