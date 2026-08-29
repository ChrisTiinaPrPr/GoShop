package org.example.goshop.payment.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@ConfigurationProperties(prefix = "goshop.payment.alipay")
public class AlipayProperties {

    private String appId;
    private String privateKey;
    private String publicKey;
    private String gateway;
    private String notifyUrl;
    private String returnUrl;
    private String frontendReturnUrl;
}
