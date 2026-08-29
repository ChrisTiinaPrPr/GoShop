package org.example.goshop.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.example.goshop.auth.entity.SysUser;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class JwtService {

    private final JwtProperties properties;
    private final SecretKey secretKey;

    public JwtService(JwtProperties properties){
        this.properties = properties;
        this.secretKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 为指定门户签发单一活动角色 Token。
     *
     * <p>即使账号同时拥有 USER、MERCHANT，买家端 Token 也只包含 USER，
     * 商家端 Token 也只包含 MERCHANT，避免一个 Token 跨端复用。</p>
     */
    public String createAccessToken(SysUser user, String activeRole, Long merchantId){
        Instant now = Instant.now();
        Instant expiresAt = now.plus(
                properties.accessTokenMinutes(),
                ChronoUnit.MINUTES
        );
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("role", activeRole)
                .claim("merchantId", merchantId)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(secretKey)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

}
