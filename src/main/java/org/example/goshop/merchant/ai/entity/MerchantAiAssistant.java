package org.example.goshop.merchant.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商家智能导购助手配置实体。
 *
 * <p>一个商家首版最多拥有一个助手，唯一性由数据库中的 merchant_id
 * 唯一键保证。模型密钥、系统提示词和工具权限不属于商家配置，始终由
 * 平台控制。</p>
 */
@Data
@TableName("merchant_ai_assistant")
public class MerchantAiAssistant {

    /** 使用项目统一的雪花 ID，不依赖数据库自增。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属商家 ID；写入时只能从商家 JWT 对应的店铺取得。 */
    private Long merchantId;

    /** 买家可见的助手名称。 */
    private String name;

    /** 自定义头像；为空时响应层降级使用店铺 Logo。 */
    private String avatarUrl;

    /** 买家首次进入助手页面时展示的欢迎语。 */
    private String welcomeMessage;

    /** 0-关闭，1-启用。新助手默认关闭，完成文档配置后再启用。 */
    private Integer enabled;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
