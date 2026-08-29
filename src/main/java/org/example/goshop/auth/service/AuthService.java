package org.example.goshop.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.goshop.auth.dto.LoginRequst;
import org.example.goshop.auth.dto.LoginResponse;
import org.example.goshop.auth.entity.SysUser;
import org.example.goshop.auth.mapper.SysUserMapper;
import org.example.goshop.auth.entity.SysUserRole;
import org.example.goshop.auth.mapper.SysUserRoleMapper;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.security.JwtService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.example.goshop.wallet.service.WalletService;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String CODE_PREFIX = "auth:code:";
    private static final String COOLDOWN_PREFIX = "auth:code:cooldown:";
    private static final String TOKEN_BLACKLIST_PREFIX = "auth:token:blacklist:";

    private final StringRedisTemplate stringRedisTemplate;
    private final JwtService jwtService;
    private final SysUserMapper sysUserMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final WalletService walletService;

    public void sendCode(String phone, String scene) {
        // 避免 login 和 LOGIN 生成不同的 Redis key
        String normalizedScene = scene.toUpperCase();
        // 冷却key
        String cooldownKey = COOLDOWN_PREFIX + normalizedScene + ":" + phone;

        // 只要存在，则代表在冷却中
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(cooldownKey))){
            throw new BusinessException(42901, "验证码发送太频繁");
        }

        String code = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        // auth:code:LOGIN:13927393401
        String codeKey = CODE_PREFIX + normalizedScene + ":" + phone;

        // 验证码保存5分钟，1分钟冷却
        stringRedisTemplate.opsForValue().set(codeKey, code, Duration.ofMinutes(5));
        // "1"没有业务作用，存在即可
        stringRedisTemplate.opsForValue().set(cooldownKey, "1", Duration.ofMinutes(1));
        log.info("发送验证码：phone={},scene={},code={}",phone,scene,code);
    }

    public LoginResponse loginBuyer(LoginRequst loginRequst) {
        consumeCode(loginRequst.phone(), loginRequst.code(), "BUYER_LOGIN");
        SysUser user = findOrCreateUser(loginRequst.phone());
        ensureRole(user.getId(), "USER");
        // 新用户首次登录时创建零余额钱包；历史用户没有钱包时也会自动补建。
        walletService.ensureWallet(user.getId());
        if(user.getStatus() == null || user.getStatus() != 1){
            throw new BusinessException(40301, "该账号已被禁用");
        }

        String token = jwtService.createAccessToken(user, "USER", null);
        return new LoginResponse(token, user.getId(), "USER", null);
    }

    /** 校验并消费指定门户的验证码，成功后验证码立即失效。 */
    public void consumeCode(String phone, String code, String scene) {
        String codeKey = CODE_PREFIX + scene.toUpperCase() + ":" + phone;
        String redisCode = stringRedisTemplate.opsForValue().get(codeKey);
        if (redisCode == null) {
            throw new BusinessException(42201, "验证码已过期，请重新获取");
        }
        if (!redisCode.equals(code)) {
            throw new BusinessException(42201, "验证码错误");
        }
        stringRedisTemplate.delete(codeKey);
    }

    /** 为用户幂等增加角色，数据库联合主键负责最终并发保护。 */
    public void ensureRole(Long userId, String role) {
        Long count = sysUserRoleMapper.selectCount(
                Wrappers.<SysUserRole>lambdaQuery()
                        .eq(SysUserRole::getUserId, userId)
                        .eq(SysUserRole::getRole, role)
        );
        if (count > 0) return;

        SysUserRole userRole = new SysUserRole();
        userRole.setUserId(userId);
        userRole.setRole(role);
        try {
            sysUserRoleMapper.insert(userRole);
        } catch (DuplicateKeyException ignored) {
            // 并发登录/注册时另一请求已写入相同角色，按幂等成功处理。
        }
    }

    public boolean hasRole(Long userId, String role) {
        return sysUserRoleMapper.selectCount(
                Wrappers.<SysUserRole>lambdaQuery()
                        .eq(SysUserRole::getUserId, userId)
                        .eq(SysUserRole::getRole, role)
        ) > 0;
    }

    public SysUser findUserByPhone(String phone) {
        return sysUserMapper.selectOne(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getPhone, phone)
        );
    }

    public String issueToken(SysUser user, String activeRole, Long merchantId) {
        return jwtService.createAccessToken(user, activeRole, merchantId);
    }

    public SysUser findOrCreateUser(String phone){
        SysUser user = sysUserMapper.selectOne(
                Wrappers.<SysUser>lambdaQuery()
                        .eq(SysUser::getPhone, phone)
        );
        if (user != null) {
            return user;
        }

        SysUser newUser = new SysUser();
        newUser.setPhone(phone);
        newUser.setNickname("用户" + phone.substring(phone.length()-4));
        newUser.setRole("USER");
        newUser.setStatus(1);

        try {
            sysUserMapper.insert(newUser);
            return newUser;
        } catch (DuplicateKeyException ignored) {
            // 并发下，可能会有重复
            return sysUserMapper.selectOne(
                    Wrappers.<SysUser>lambdaQuery()
                            .eq(SysUser::getPhone, phone)
            );
        }
    }
    public void logout(String token) {
        Claims claims = jwtService.parse(token);

        long seconds = Duration.between(
                Instant.now(),
                claims.getExpiration().toInstant()
        ).getSeconds();

        // Redis黑名单有效期与原 Token 剩余有效期一致。
        if (seconds > 0) {
            String jti = claims.getId();
            stringRedisTemplate.opsForValue().set(
                    TOKEN_BLACKLIST_PREFIX + jti,
                    "1",
                    Duration.ofSeconds(seconds)
            );
        }
    }
}
