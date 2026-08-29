package org.example.goshop.product.cache;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 公开商品详情缓存参数。
 *
 * <p>所有时间都可通过环境变量覆盖。默认值偏向单体项目的稳定演示：
 * 正常详情缓存较久，空值缓存较短，互斥锁只覆盖一次数据库重建时间。</p>
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "goshop.product-cache")
public class ProductCacheProperties {

    private boolean enabled = true;

    @NotNull
    private Duration detailTtl = Duration.ofMinutes(30);

    @NotNull
    private Duration nullTtl = Duration.ofMinutes(2);

    @NotNull
    private Duration maxTtlJitter = Duration.ofMinutes(10);

    @NotNull
    private Duration lockTtl = Duration.ofSeconds(10);

    @NotNull
    private Duration lockWait = Duration.ofMillis(30);

    @Min(1)
    private int lockRetryTimes = 8;
}
