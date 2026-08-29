package org.example.goshop.merchant.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.example.goshop.merchant.ai.entity.MerchantAiAssistant;

/**
 * 商家智能导购助手配置 Mapper。
 */
@Mapper
public interface MerchantAiAssistantMapper
        extends BaseMapper<MerchantAiAssistant> {

    /**
     * 按商家查询唯一助手配置。
     *
     * <p>merchant_id 不是由前端传入，而是 Service 从当前商家身份恢复。
     * LIMIT 1 是防御性限制，数据库唯一键仍是最终一致性保护。</p>
     */
    @Select("""
        SELECT *
        FROM merchant_ai_assistant
        WHERE merchant_id = #{merchantId}
        LIMIT 1
        """)
    MerchantAiAssistant selectByMerchantId(
            @Param("merchantId") Long merchantId
    );

    /**
     * 按 merchant_id 原子创建或更新助手配置。
     *
     * <p>唯一键配合 ON DUPLICATE KEY UPDATE，可避免同一商家首次并发
     * 保存时产生两条配置。发生更新时保留原记录 ID 和创建时间。</p>
     */
    @Insert("""
        INSERT INTO merchant_ai_assistant
            (id, merchant_id, name, avatar_url, welcome_message, enabled)
        VALUES
            (#{id}, #{merchantId}, #{name}, #{avatarUrl},
             #{welcomeMessage}, #{enabled})
        ON DUPLICATE KEY UPDATE
            name = VALUES(name),
            avatar_url = VALUES(avatar_url),
            welcome_message = VALUES(welcome_message),
            enabled = VALUES(enabled),
            updated_at = CURRENT_TIMESTAMP(3)
        """)
    int upsert(MerchantAiAssistant assistant);
}
