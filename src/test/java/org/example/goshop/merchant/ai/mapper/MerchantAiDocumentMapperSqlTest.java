package org.example.goshop.merchant.ai.mapper;

import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文档处理状态 SQL 的注解级回归测试。
 *
 * <p>注解 SQL 不经过 Mapper XML 的实体解码。曾经把小于号写成
 * {@code &amp;lt;}，编译可以通过，但 MySQL 会收到非法字符并在运行时返回
 * 语法错误，使解析接口直接响应 500。本测试用于阻止该问题再次出现。</p>
 */
class MerchantAiDocumentMapperSqlTest {

    @Test
    void markProcessingShouldUseRawLessThanOperator() throws Exception {
        Method method = MerchantAiDocumentMapper.class.getMethod(
                "markProcessing",
                Long.class,
                Long.class,
                LocalDateTime.class,
                LocalDateTime.class
        );
        Update update = method.getAnnotation(Update.class);
        String sql = String.join("\n", update.value());

        assertTrue(
                sql.contains("updated_at < #{staleBefore}"),
                "Java 注解 SQL 应直接使用小于号"
        );
        assertFalse(
                sql.contains("&lt;"),
                "Java 注解 SQL 不能包含 XML 实体"
        );
        assertTrue(
                sql.contains("'READY'"),
                "已完成文档必须允许重建新版本向量索引"
        );
    }
}
