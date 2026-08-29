package org.example.goshop.merchant.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

/** 店铺资料更新；字段不传表示保持不变，空简介表示清空。 */
@Data
public class UpdateMerchantProfileRequest {
    @Size(max = 100, message = "店铺名称长度不能超过100个字符")
    private String name;

    @Size(max = 1000, message = "店铺简介长度不能超过1000个字符")
    private String description;

    private MultipartFile logo;
}
