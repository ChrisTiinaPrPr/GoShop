package org.example.goshop.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity httpSecurity,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler
    ) throws Exception {

        return httpSecurity
                // 前后端分离项目一般关闭 CSRF
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                // JWT 不使用 Session 保存登录状态
                .sessionManagement(session -> session.sessionCreationPolicy(
                        SessionCreationPolicy.STATELESS)
                ).exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/swagger-ui/**",
                                "/v3/api-docs/**"
                        ).permitAll()
                        // 浏览器握手阶段无法可靠携带 Authorization；JWT 在 STOMP CONNECT 帧校验。
                        // 这里只允许升级到 WebSocket，不代表未登录客户端可以订阅或收发消息。
                        .requestMatchers(HttpMethod.GET, "/ws/chat").permitAll()
                        // 只有获取验证码、登录、注册公开；两个 logout 仍由各自角色保护。
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/buyer/auth/code",
                                "/api/v1/buyer/auth/login",
                                "/api/v1/merchant/auth/code",
                                "/api/v1/merchant/auth/login",
                                "/api/v1/merchant/auth/register"
                        ).permitAll()
                        /*
                         * 店铺资料和商品允许游客浏览，但 AI 提问会消耗模型
                         * 资源并需要绑定买家身份，必须放在公开通配规则之前。
                         */
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/buyer/merchants/*/ai-assistant/questions"
                        ).hasRole("USER")
                        .requestMatchers(
                                "/api/v1/buyer/products/**",
                                "/api/v1/buyer/categories/**",
                                "/api/v1/buyer/merchants/**"
                        ).permitAll()
                        .requestMatchers("/api/v1/merchant/**")
                        .hasRole("MERCHANT")
                        .requestMatchers("/api/v1/buyer/**")
                        .hasRole("USER")
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/payments/alipay/callback"
                        ).permitAll()
                        // 支付宝支付完成后由用户浏览器访问，无 JWT，因此必须公开。
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/payments/alipay/return"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(
                        jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class
                )
                .cors(Customizer.withDefaults())
                .build();
    }
}
