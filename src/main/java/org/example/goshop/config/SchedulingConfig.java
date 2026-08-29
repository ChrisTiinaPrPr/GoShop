package org.example.goshop.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 开启定时任务
 * 用于定期扫描超过付款截止时间、仍未付款的订单
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

}
