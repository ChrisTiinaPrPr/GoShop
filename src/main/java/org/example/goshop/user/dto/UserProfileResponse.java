package org.example.goshop.user.dto;

import org.example.goshop.auth.entity.SysUser;

/**
 * 返回给前端的当前用户信息
 * 不返回 status 等内部管理字段
 * @param id
 * @param phone
 * @param nickname
 * @param role
 */
public record UserProfileResponse(
        Long id,
        String phone,
        String nickname,
        String role,
        String avatarUrl
) {
    public static UserProfileResponse from (SysUser user) {
        return new UserProfileResponse(
                user.getId(),
                user.getPhone(),
                user.getNickname(),
                // 这是买家门户的 /me 响应，活动角色固定为 USER；不能再读取迁移期旧字段。
                "USER",
                user.getAvatarUrl()
        );
    }
}
