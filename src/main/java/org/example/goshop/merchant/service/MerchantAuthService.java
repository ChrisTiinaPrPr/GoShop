package org.example.goshop.merchant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.example.goshop.auth.dto.LoginRequst;
import org.example.goshop.auth.dto.LoginResponse;
import org.example.goshop.auth.entity.SysUser;
import org.example.goshop.auth.service.AuthService;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.merchant.entity.Merchant;
import org.example.goshop.merchant.mapper.MerchantMapper;
import org.springframework.stereotype.Service;

/** 商家门户登录编排，只允许已经开通 MERCHANT 角色的账号进入。 */
@Service
@RequiredArgsConstructor
public class MerchantAuthService {
    private final AuthService authService;
    private final MerchantMapper merchantMapper;

    public LoginResponse login(LoginRequst request) {
        authService.consumeCode(request.phone(), request.code(), "MERCHANT_LOGIN");
        SysUser user = authService.findUserByPhone(request.phone());
        if (user == null) {
            throw new BusinessException(40301, "该手机号尚未开通商家");
        }
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            throw new BusinessException(40301, "该账号已被禁用");
        }
        if (!authService.hasRole(user.getId(), "MERCHANT")) {
            throw new BusinessException(40301, "该账号尚未开通商家");
        }

        Merchant merchant = merchantMapper.selectOne(
                new LambdaQueryWrapper<Merchant>()
                        .eq(Merchant::getUserId, user.getId())
                        .eq(Merchant::getStatus, 1)
        );
        if (merchant == null) {
            throw new BusinessException(40301, "商家不存在或已停用");
        }

        String token = authService.issueToken(user, "MERCHANT", merchant.getId());
        return new LoginResponse(token, user.getId(), "MERCHANT", merchant.getId());
    }
}
