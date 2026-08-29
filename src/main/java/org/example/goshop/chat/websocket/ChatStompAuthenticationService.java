package org.example.goshop.chat.websocket;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.example.goshop.security.JwtService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 将 STOMP CONNECT 原生头中的 Bearer Token 转换为聊天连接身份。
 *
 * <p>浏览器 WebSocket 握手无法稳定携带自定义 Authorization 请求头，因此 HTTP 握手只负责
 * 升级协议，真正的 JWT 校验放在 STOMP CONNECT 阶段。该服务复用现有 {@link JwtService}
 * 的签名、有效期校验，并检查与 HTTP 鉴权相同的 Redis 注销黑名单。</p>
 */
@Component
@RequiredArgsConstructor
public class ChatStompAuthenticationService {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String TOKEN_BLACKLIST_PREFIX = "auth:token:blacklist:";

    private final JwtService jwtService;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 校验 CONNECT Authorization 头并返回 Spring Security Authentication。
     *
     * @param authorization STOMP 原生 Authorization 头
     * @return 已认证且可作为 WebSocket Principal 使用的身份
     * @throws BadCredentialsException Token 缺失、无效、过期、已注销或门户 Claims 不完整
     */
    public Authentication authenticate(String authorization) {
        if (!StringUtils.hasText(authorization) || !authorization.startsWith(BEARER_PREFIX)) {
            throw new BadCredentialsException("STOMP CONNECT 缺少 Bearer Token");
        }

        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        if (!StringUtils.hasText(token)) {
            throw new BadCredentialsException("STOMP CONNECT 的 Bearer Token 为空");
        }

        try {
            Claims claims = jwtService.parse(token);
            ensureTokenNotRevoked(claims);

            Long userId = parsePositiveLong(claims.getSubject(), "subject");
            String role = claims.get("role", String.class);
            Long merchantId = parseOptionalLong(claims.get("merchantId"));
            ChatPrincipal principal = new ChatPrincipal(userId, role, merchantId);

            // Authentication 不保存 JWT 原文，降低连接对象或诊断信息意外泄露 Token 的风险。
            return UsernamePasswordAuthenticationToken.authenticated(
                    principal,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + role))
            );
        } catch (BadCredentialsException exception) {
            throw exception;
        } catch (JwtException | IllegalArgumentException exception) {
            // 对客户端统一返回认证失败，不暴露签名、Claims 或解析实现细节。
            throw new BadCredentialsException("STOMP CONNECT Token 无效或已过期", exception);
        } catch (RuntimeException exception) {
            // Redis 黑名单不可用时安全失败，不能绕过注销状态建立长连接。
            throw new BadCredentialsException("STOMP CONNECT Token 状态校验失败", exception);
        }
    }

    private void ensureTokenNotRevoked(Claims claims) {
        String jti = claims.getId();
        if (!StringUtils.hasText(jti)) {
            throw new BadCredentialsException("STOMP CONNECT Token 缺少 jti");
        }
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(TOKEN_BLACKLIST_PREFIX + jti))) {
            throw new BadCredentialsException("STOMP CONNECT Token 已注销");
        }
    }

    private Long parsePositiveLong(String value, String claimName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("JWT 缺少 " + claimName);
        }
        long parsed = Long.parseLong(value);
        if (parsed <= 0) {
            throw new IllegalArgumentException("JWT " + claimName + " 无效");
        }
        return parsed;
    }

    private Long parseOptionalLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(value.toString());
    }
}
