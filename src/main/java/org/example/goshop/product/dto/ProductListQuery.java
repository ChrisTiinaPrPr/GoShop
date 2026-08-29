package org.example.goshop.product.dto;

/**
 * 已上架商品的内部查询条件。
 *
 * <p>该对象由 Service 创建，不直接接收前端或模型的原始参数，
 * 因此排序值必须由 ProductSort 枚举转换得到。</p>
 */
public record ProductListQuery(
        /**
         * 限定公开商品所属商家。
         *
         * <p>null 表示平台公开商品列表；非 null 表示店铺公开商品列表。
         * 该值只能由 Service 根据店铺上下文写入，不能直接绑定前端 DTO
         * 或模型工具参数。</p>
         */
        Long merchantId,

        Long categoryId,
        String keyword,

        /**
         * SKU 最低价格，单位为分。
         * null 表示不设置最低价格。
         */
        Long minPriceCent,

        /**
         * SKU 最高价格，单位为分。
         * null 表示不设置最高价格。
         */
        Long maxPriceCent,

        /**
         * ProductSort 枚举名称，例如 PRICE_ASC。
         * Mapper 只能匹配固定枚举值，不能拼接任意 SQL。
         */
        String sort
) {
}
