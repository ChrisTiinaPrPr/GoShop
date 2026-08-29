package org.example.goshop.wallet.entity;


import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户钱包
 *  <p>金额统一使用“分”禁止使用double 或 BigDecimal 直接保存余额<p/>
 */
@Data
@TableName("user_wallet")
public class UserWallet {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    // 钱包所属用户，一个用户只能有一个钱包
    private Long userId;

    // 当前可用余额，单位：分
    private Long balanceCent;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
