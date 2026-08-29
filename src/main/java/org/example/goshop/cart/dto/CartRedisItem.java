package org.example.goshop.cart.dto;

public record CartRedisItem(
        Integer quantity, // 商品数量
        Boolean selected // 是否被选中
) {
}
