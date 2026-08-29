package org.example.goshop.merchant.ai.service;

import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.merchant.ai.dto.MerchantAiAssistantResponse;
import org.example.goshop.merchant.ai.dto.SaveMerchantAiAssistantRequest;
import org.example.goshop.merchant.ai.entity.MerchantAiAssistant;
import org.example.goshop.merchant.ai.mapper.MerchantAiAssistantMapper;
import org.example.goshop.merchant.dto.MerchantProfileResponse;
import org.example.goshop.merchant.service.MerchantService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商家智能导购助手配置查询测试。
 */
@ExtendWith(MockitoExtension.class)
class MerchantAiAssistantServiceTest {

    @Mock
    private MerchantService merchantService;

    @Mock
    private MerchantAiAssistantMapper assistantMapper;

    @InjectMocks
    private MerchantAiAssistantService assistantService;

    @Test
    void shouldReturnConfiguredAssistantForCurrentMerchant() {
        long userId = 1001L;
        long merchantId = 7001L;
        LocalDateTime updatedAt =
                LocalDateTime.of(2026, 8, 10, 21, 30);

        MerchantProfileResponse merchant =
                merchant(
                        merchantId,
                        "星环数码",
                        "https://img.test/store.png"
                );

        MerchantAiAssistant assistant =
                new MerchantAiAssistant();
        assistant.setId(8001L);
        assistant.setMerchantId(merchantId);
        assistant.setName("星环选购顾问");
        assistant.setAvatarUrl(
                "https://img.test/assistant.png"
        );
        assistant.setWelcomeMessage(
                "您好，请告诉我您的使用场景。"
        );
        assistant.setEnabled(1);
        assistant.setUpdatedAt(updatedAt);

        when(merchantService.getCurrentMerchantProfile(
                userId
        )).thenReturn(merchant);
        when(assistantMapper.selectByMerchantId(
                merchantId
        )).thenReturn(assistant);

        MerchantAiAssistantResponse response =
                assistantService.getCurrentAssistant(
                        userId
                );

        assertEquals(8001L, response.id());
        assertEquals(merchantId, response.merchantId());
        assertEquals("星环选购顾问", response.name());
        assertEquals(
                "https://img.test/assistant.png",
                response.avatarUrl()
        );
        assertTrue(response.enabled());
        assertTrue(response.configured());
        assertEquals(updatedAt, response.updatedAt());
    }

    @Test
    void shouldReturnReadOnlyPreviewWhenNotConfigured() {
        long userId = 1002L;
        long merchantId = 7002L;

        MerchantProfileResponse merchant =
                merchant(
                        merchantId,
                        "远山户外",
                        "https://img.test/outdoor.png"
                );

        when(merchantService.getCurrentMerchantProfile(
                userId
        )).thenReturn(merchant);
        when(assistantMapper.selectByMerchantId(
                merchantId
        )).thenReturn(null);

        MerchantAiAssistantResponse response =
                assistantService.getCurrentAssistant(
                        userId
                );

        assertNull(response.id());
        assertEquals(merchantId, response.merchantId());
        assertEquals("远山户外智能导购", response.name());
        assertEquals(
                "https://img.test/outdoor.png",
                response.avatarUrl()
        );
        assertFalse(response.enabled());
        assertFalse(response.configured());
        assertNull(response.updatedAt());

        /* GET 只查询，不得为了默认预览调用 insert。 */
        verify(assistantMapper, never()).insert(
                org.mockito.ArgumentMatchers.any(
                        MerchantAiAssistant.class
                )
        );
    }

    @Test
    void shouldNotQueryAssistantWhenMerchantIdentityInvalid() {
        long userId = 1003L;

        when(merchantService.getCurrentMerchantProfile(
                userId
        )).thenThrow(
                new BusinessException(
                        40301,
                        "商家不存在或已停用"
                )
        );

        assertThrows(
                BusinessException.class,
                () -> assistantService
                        .getCurrentAssistant(userId)
        );

        verify(assistantMapper, never())
                .selectByMerchantId(
                        org.mockito.ArgumentMatchers.anyLong()
                );
    }

    @Test
    void shouldUpsertNormalizedConfigurationForCurrentMerchant() {
        long userId = 1004L;
        long merchantId = 7004L;
        MerchantProfileResponse merchant = merchant(
                merchantId,
                "晴空家居",
                "https://img.test/home.png"
        );
        SaveMerchantAiAssistantRequest request =
                new SaveMerchantAiAssistantRequest(
                        "  晴空搭配顾问  ",
                        "   ",
                        "  您好，请告诉我房间面积。  ",
                        false
                );

        MerchantAiAssistant persisted =
                new MerchantAiAssistant();
        persisted.setId(8004L);
        persisted.setMerchantId(merchantId);
        persisted.setName("晴空搭配顾问");
        persisted.setAvatarUrl(null);
        persisted.setWelcomeMessage(
                "您好，请告诉我房间面积。"
        );
        persisted.setEnabled(0);

        when(merchantService.getCurrentMerchantProfile(
                userId
        )).thenReturn(merchant);
        when(assistantMapper.selectByMerchantId(
                merchantId
        )).thenReturn(persisted);

        MerchantAiAssistantResponse response =
                assistantService.saveCurrentAssistant(
                        userId,
                        request
                );

        ArgumentCaptor<MerchantAiAssistant> captor =
                ArgumentCaptor.forClass(
                        MerchantAiAssistant.class
                );
        verify(assistantMapper).upsert(captor.capture());
        MerchantAiAssistant saved = captor.getValue();
        assertEquals(merchantId, saved.getMerchantId());
        assertEquals("晴空搭配顾问", saved.getName());
        assertNull(saved.getAvatarUrl());
        assertEquals(
                "您好，请告诉我房间面积。",
                saved.getWelcomeMessage()
        );
        assertEquals(0, saved.getEnabled());
        assertEquals(8004L, response.id());
        /* 未配置自定义头像时，响应应回退为店铺 Logo。 */
        assertEquals(
                "https://img.test/home.png",
                response.avatarUrl()
        );
    }

    @Test
    void shouldSaveEnabledConfigurationWithCustomAvatar() {
        long userId = 1005L;
        long merchantId = 7005L;
        MerchantProfileResponse merchant = merchant(
                merchantId,
                "原野露营",
                "https://img.test/camp-store.png"
        );
        SaveMerchantAiAssistantRequest request =
                new SaveMerchantAiAssistantRequest(
                        "原野装备顾问",
                        "https://img.test/camp-ai.png",
                        "告诉我人数和出行季节，我来推荐装备。",
                        true
                );

        MerchantAiAssistant persisted =
                new MerchantAiAssistant();
        persisted.setId(8005L);
        persisted.setMerchantId(merchantId);
        persisted.setName(request.name());
        persisted.setAvatarUrl(request.avatarUrl());
        persisted.setWelcomeMessage(
                request.welcomeMessage()
        );
        persisted.setEnabled(1);

        when(merchantService.getCurrentMerchantProfile(
                userId
        )).thenReturn(merchant);
        when(assistantMapper.selectByMerchantId(
                merchantId
        )).thenReturn(persisted);

        MerchantAiAssistantResponse response =
                assistantService.saveCurrentAssistant(
                        userId,
                        request
                );

        ArgumentCaptor<MerchantAiAssistant> captor =
                ArgumentCaptor.forClass(
                        MerchantAiAssistant.class
                );
        verify(assistantMapper).upsert(captor.capture());
        assertEquals(1, captor.getValue().getEnabled());
        assertTrue(response.enabled());
        assertEquals(
                request.avatarUrl(),
                response.avatarUrl()
        );
    }

    @Test
    void shouldNotUpsertWhenMerchantIdentityInvalid() {
        long userId = 1006L;
        SaveMerchantAiAssistantRequest request =
                new SaveMerchantAiAssistantRequest(
                        "不可保存的助手",
                        null,
                        "欢迎语",
                        false
                );

        when(merchantService.getCurrentMerchantProfile(
                userId
        )).thenThrow(
                new BusinessException(
                        40301,
                        "商家不存在或已停用"
                )
        );

        assertThrows(
                BusinessException.class,
                () -> assistantService
                        .saveCurrentAssistant(
                                userId,
                                request
                        )
        );

        verify(assistantMapper, never()).upsert(
                org.mockito.ArgumentMatchers.any(
                        MerchantAiAssistant.class
                )
        );
    }

    private MerchantProfileResponse merchant(
            Long merchantId,
            String name,
            String logoUrl
    ) {
        return new MerchantProfileResponse(
                merchantId,
                name,
                logoUrl,
                null
        );
    }
}
