package org.example.goshop.wallet.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 钱包资金流水
 */
@Data
@TableName("wallet_transaction")
public class WalletTransaction {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    // 钱包流水号
    private String transactionNo;

    private Long userId;

    // 业务类型，如：ORDER_PAYMENT,ORDER_REFUND
    private String bizType;

    // 业务唯一编号；订单支付时使用商城支付单号
    private String bizNo;

    // 资金方向：IN 表示收入，OUT表示支出
    private String direction;

    // 金额，单位：分
    private Long amountCent;

    private Long balanceBeforeCent;
    private Long balanceAfterCent;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
