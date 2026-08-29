package org.example.goshop.merchant.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import java.util.Locale;

/** 商家导购文档分页与状态筛选参数。 */
@Schema(
        name = "MerchantAiDocumentListQuery",
        description = "导购文档分页查询参数"
)
public record MerchantAiDocumentListQuery(
        @Schema(description = "页码，从 1 开始", example = "1")
        @Min(value = 1, message = "页码不能小于1")
        Long page,

        @Schema(description = "每页数量，默认 20，最大 100", example = "20")
        @Min(value = 1, message = "每页数量不能小于1")
        @Max(value = 100, message = "每页数量不能超过100")
        Long pageSize,

        @Schema(
                description = "可选处理状态：UPLOADED、PROCESSING、READY、FAILED",
                example = "READY"
        )
        @Pattern(
                regexp = "(?i)^\\s*(?:UPLOADED|PROCESSING|READY|FAILED)?\\s*$",
                message = "文档状态只能是UPLOADED、PROCESSING、READY或FAILED"
        )
        String status
) {

    @JsonIgnore
    @Schema(hidden = true)
    public long effectivePage() {
        return page == null ? 1L : page;
    }

    @JsonIgnore
    @Schema(hidden = true)
    public long effectivePageSize() {
        return pageSize == null ? 20L : pageSize;
    }

    /** 空白状态表示查询全部；非空状态统一转换成数据库中的大写枚举值。 */
    @JsonIgnore
    @Schema(hidden = true)
    public String normalizedStatus() {
        if (status == null || status.isBlank()) {
            return null;
        }
        return status.trim().toUpperCase(Locale.ROOT);
    }
}
