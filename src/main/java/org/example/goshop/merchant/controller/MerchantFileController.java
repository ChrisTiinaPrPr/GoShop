package org.example.goshop.merchant.controller;


import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.example.goshop.common.api.Result;
import org.example.goshop.merchant.dto.MerchantProductImageResponse;
import org.example.goshop.merchant.service.MerchantProductImageService;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "商家文件管理")
@RestController
@RequestMapping("/api/v1/merchant/files")
@RequiredArgsConstructor
@Validated
public class MerchantFileController {

    private final MerchantProductImageService merchantProductImageService;

    @Operation(summary = "上传当前商家商品的附加图片")
    @PostMapping(value = "/images",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<MerchantProductImageResponse> uploadProductImage(
            Authentication authentication,

            @RequestParam
            @Positive(message = "spu Id不能小于0")
            Long spuId,

            @RequestParam(defaultValue = "0")
            @PositiveOrZero(message = "sort 不能小于 0")
            Integer sort,

            @RequestPart("file")
            MultipartFile file
    ) {
        Long userID = (Long) authentication.getPrincipal();

        return Result.ok(merchantProductImageService.uploadCurrentMerchantProductImage(userID, spuId, sort, file));
    }

    @Operation(summary = "删除当前商家商品的附加图片")
    @DeleteMapping("/products/{spuId}/images/{imageId}")
    public Result<Void> deleteProductImage(
            Authentication authentication,

            @PathVariable
            @Positive(message = "spu Id必须大于 0")
            Long spuId,

            @PathVariable
            @Positive(message = "image Id必须大于 0")
            Long imageId
    ) {
        Long userId = (Long) authentication.getPrincipal();
        merchantProductImageService.deleteCurrentMerchantProductImage(
                userId,
                spuId,
                imageId
        );
        return Result.ok();
    }
}
