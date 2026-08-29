package org.example.goshop.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@Configuration
public class JacksonConfig {

    /**
     * 供现有 Security 组件使用的 Jackson 2 ObjectMapper。
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                // 供订单幂等结果中的 LocalDateTime 正确序列化和反序列化。
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Spring Boot 4 的 Web JSON 使用 Jackson 3。
     * 将所有 Long / long 输出为字符串，避免前端和 Swagger 的 Number 精度丢失。
     */
    @Bean
    public JsonMapperBuilderCustomizer longAsStringCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule();

            module.addSerializer(Long.class, ToStringSerializer.instance);
            module.addSerializer(Long.TYPE, ToStringSerializer.instance);

            builder.addModule(module);
        };
    }
}