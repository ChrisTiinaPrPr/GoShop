package org.example.goshop.product.dto;

import org.example.goshop.common.exception.BusinessException;

public enum ProductSort {
    LATEST("latest"),
    SALES("sales"),
    PRICE_ASC("priceAsc"),
    PRICE_DESC("priceDesc");

    private final String apiValue;

    ProductSort(String apiValue) {
        this.apiValue = apiValue;
    }

    public static ProductSort fromApiValue(String apiValue) {
        for (ProductSort value : values()) {
            if (value.apiValue.equals(apiValue)) {
                return value;
            }
        }
        throw new BusinessException(40001, "sort参数不合法");
    }
}
