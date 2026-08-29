package org.example.goshop.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 下单幂等事实记录。
 *
 * <p>Redis 只承担快速拦截和结果缓存；该表与订单在同一个 MySQL 事务中提交，
 * 因此 Redis 写入失败或缓存过期后，服务端仍能按用户和幂等键恢复首次结果。</p>
 */
@Data
@TableName("order_submit_record")
public class OrderSubmitRecord {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;
    private String idempotencyKey;

    /** 规范化请求的 SHA-256，用于拒绝同一 Key 对应不同请求体。 */
    private String requestHash;

    /** PROCESSING 仅存在于事务内部；事务提交时必须已经变为 COMPLETED。 */
    private String status;

    /** 首次成功创建的完整响应，供 Redis 丢失后恢复。 */
    private String responseJson;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
