package org.example.goshop.user.dto;

import jakarta.validation.constraints.Size;


public record UpdateUserProfileRequest(
        @Size(min = 1, max = 50,message = "昵称长度必须为 1 到 50 个字符")
        String nickname,

        @Size(max = 500)
        String avatarUrl
) {
}
