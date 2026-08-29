package org.example.goshop.merchant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("merchant")
public class Merchant {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    // 商家所属账户；公开接口不向客户端返回此字段
    private Long userId;

    private String name;
    private String logoUrl;
    private String description;

    // 0: 停用 1: 正常
    private Integer status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 用于后续更新 LOGO 或注销商家时删除 OSS 对象
    private String logoObjectKey;
}
