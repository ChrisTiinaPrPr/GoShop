package org.example.goshop.user.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

@Data
public class UpdateProfileRequest {

    @Size(max = 50,message = "昵称不能超过50个字符")
    private String nickName;

    private MultipartFile avatar;
}
