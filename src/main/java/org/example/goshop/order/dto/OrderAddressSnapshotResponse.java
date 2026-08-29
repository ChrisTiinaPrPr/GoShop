package org.example.goshop.order.dto;

/**
 * 下单时保存的收货地址快照
 * 返回快照而不是当前地址，保证历史订单信息不会被后续改地址影响
 */
public record OrderAddressSnapshotResponse(
        String receiver,
        String phone,
        String province,
        String city,
        String district,
        String detail
) {
}
