package org.example.goshop.infrastructure.mq.outbox;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import lombok.Setter;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface MqOutboxMapper extends BaseMapper<MqOutbox> {

    /**
     * 只查询少量 ID， 不在批量查询阶段持有长时间行锁
     */
    @Select("""
        SELECT id
        FROM mq_outbox
        WHERE status IN ('NEW', 'RETRY')
          AND next_retry_at <= #{now}
        ORDER BY created_at ASC
        LIMIT #{limit}
        """)
    List<Long> selectReadyIds(
            @Param("now") LocalDateTime now,
            @Param("limit") int limit
    );

    /**
     * 每条消息发送前加行锁。
     *
     * <p>以后即使启动多个 GoShop 实例，同一条 Outbox 也不会被两个实例
     * 同时处理。发送成功后更新为 SENT。</p>
     */
    @Select("""
        SELECT *
        FROM mq_outbox
        WHERE id = #{id}
        FOR UPDATE
        """)
    MqOutbox selectByIdForUpdate(@Param("id") Long id);

    @Update("""
        UPDATE mq_outbox
        SET status = 'SENT',
            sent_at = CURRENT_TIMESTAMP(3),
            last_error = NULL,
            updated_at = CURRENT_TIMESTAMP(3)
        WHERE id = #{id}
        """)
    int markSent(@Param("id") Long id);

    @Update("""
        UPDATE mq_outbox
        SET status = #{status},
            retry_count = #{retryCount},
            next_retry_at = #{nextRetryAt},
            last_error = #{lastError},
            updated_at = CURRENT_TIMESTAMP(3)
        WHERE id = #{id}
        """)
    int markRetry(
            @Param("id") Long id,
            @Param("status") String status,
            @Param("retryCount") int retryCount,
            @Param("nextRetryAt") LocalDateTime nextRetryAt,
            @Param("lastError") String lastError
    );
}
