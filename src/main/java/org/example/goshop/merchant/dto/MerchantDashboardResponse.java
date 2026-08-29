package org.example.goshop.merchant.dto;

public record MerchantDashboardResponse(
        Long todayPaidOrderCount,
        Long todayPaidAmountCent,
        Long waitingShipmentCount,
        Long pendingRefundCount,
        Long onSaleProductCount,
        Long lowStockSkuCount
) {
}
