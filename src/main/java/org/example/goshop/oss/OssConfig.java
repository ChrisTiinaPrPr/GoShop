package org.example.goshop.oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OssProperties.class)
public class OssConfig {

    @Bean(destroyMethod = "shutdown")
    public OSS ossClient(OssProperties ossProperties) {
        // OSS Client 是可复用对象；应用关闭时由 spring 调用 shutdown 方法关闭
        return new OSSClientBuilder().build(
                ossProperties.endpoint(),
                ossProperties.accessKeyId(),
                ossProperties.accessKeySecret()
        );
    }
}
