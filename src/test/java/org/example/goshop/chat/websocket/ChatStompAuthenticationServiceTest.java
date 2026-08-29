package org.example.goshop.chat.websocket;

import org.example.goshop.auth.entity.SysUser;
import org.example.goshop.security.JwtProperties;
import org.example.goshop.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatStompAuthenticationServiceTest {

    private static final String JWT_SECRET = "chat-websocket-test-secret-key-at-least-32-bytes";

    private StringRedisTemplate redisTemplate;
    private JwtService jwtService;
    private ChatStompAuthenticationService authenticationService;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        jwtService = new JwtService(new JwtProperties(JWT_SECRET, 120));
        authenticationService = new ChatStompAuthenticationService(jwtService, redisTemplate);
        when(redisTemplate.hasKey(startsWith("auth:token:blacklist:"))).thenReturn(false);
    }

    @Test
    void shouldCreatePortalIsolatedPrincipalForBuyerAndMerchant() {
        SysUser user = user(1001L);
        String buyerToken = jwtService.createAccessToken(user, "USER", null);
        String merchantToken = jwtService.createAccessToken(user, "MERCHANT", 9001L);

        Authentication buyer = authenticationService.authenticate("Bearer " + buyerToken);
        Authentication merchant = authenticationService.authenticate("Bearer " + merchantToken);

        ChatPrincipal buyerPrincipal = assertInstanceOf(ChatPrincipal.class, buyer.getPrincipal());
        ChatPrincipal merchantPrincipal = assertInstanceOf(ChatPrincipal.class, merchant.getPrincipal());
        assertEquals("buyer:1001", buyerPrincipal.getName());
        assertEquals("merchant:9001", merchantPrincipal.getName());
        assertEquals(List.of("ROLE_USER"), buyer.getAuthorities().stream()
                .map(Object::toString)
                .toList());
        assertEquals(List.of("ROLE_MERCHANT"), merchant.getAuthorities().stream()
                .map(Object::toString)
                .toList());
    }

    @Test
    void shouldRejectTokenAlreadyPlacedInLogoutBlacklist() {
        SysUser user = user(1001L);
        String token = jwtService.createAccessToken(user, "USER", null);
        when(redisTemplate.hasKey(startsWith("auth:token:blacklist:"))).thenReturn(true);

        assertThrows(
                BadCredentialsException.class,
                () -> authenticationService.authenticate("Bearer " + token)
        );
    }

    @Test
    void shouldRejectMissingBearerHeader() {
        assertThrows(
                BadCredentialsException.class,
                () -> authenticationService.authenticate(null)
        );
        assertThrows(
                BadCredentialsException.class,
                () -> authenticationService.authenticate("not-a-bearer-token")
        );
    }

    private SysUser user(Long id) {
        SysUser user = new SysUser();
        user.setId(id);
        return user;
    }
}
