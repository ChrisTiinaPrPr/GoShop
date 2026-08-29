package org.example.goshop.security;


import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Filter 的主要工作是：
 * 1. 从请求头中获取 JWT
 * 2. 校验JWT
 * 3. 获取用户信息，把用户信息保存在 SecurityContext 中。
 * 5. 继续执行后面的过滤器。
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String TOKEN_BLACK_LIST_PREFIX = "auth:token:blacklist:";
    private final JwtService jwtService;
    private final StringRedisTemplate stringRedisTemplate;


    /**
     * SSE 使用 Spring MVC 异步请求。
     *
     * <p>OncePerRequestFilter 默认跳过 DispatcherType.ASYNC，
     * 但 Spring Security 会在异步完成分派时再次执行授权规则。
     * 因此需要让 JWT Filter 在 ASYNC 分派中重新恢复认证信息。</p>
     *
     * @return false 表示不要跳过异步分派
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        // 获取 Authorization 请求头
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        // 未携带 Bearer Token 时交给后续 Security 规则判断是否允许访问。
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }
        // 去掉 Bearer 前缀
        String token = authorization.substring(7);

        try {
            Claims claims = jwtService.parse(token);

            // 注销后的 token 的 jti 会写入 Redis 的黑名单。
            String jti = claims.getId();
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(TOKEN_BLACK_LIST_PREFIX + jti))) {
                filterChain.doFilter(request, response);
                return;
            }
            Long userId = Long.valueOf(claims.getSubject());
            String role = claims.get("role", String.class);

            var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
            /*
             * 创建认证信息。
             *
             * 第一个参数：当前用户
             * 第二个参数：密码，JWT认证中不需要，所以为 null
             * 第三个参数：用户权限
             */
            var authentication = new UsernamePasswordAuthenticationToken(
                    userId,
                    null,
                    authorities
            );

            authentication.setDetails(
                    new WebAuthenticationDetailsSource().buildDetails(request)
            );
            // 将认证信息保存在 SecurityContext 中
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (JwtException | IllegalArgumentException ignored) {
            // 无效、篡改、过期 Token 不应该访问。
            SecurityContextHolder.clearContext();
        }
        // 继续执行后面的过滤器。
        filterChain.doFilter(request, response);
    }
}
