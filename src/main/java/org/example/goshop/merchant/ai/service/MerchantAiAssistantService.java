package org.example.goshop.merchant.ai.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.merchant.ai.dto.MerchantAiAssistantResponse;
import org.example.goshop.merchant.ai.dto.SaveMerchantAiAssistantRequest;
import org.example.goshop.merchant.ai.entity.MerchantAiAssistant;
import org.example.goshop.merchant.ai.mapper.MerchantAiAssistantMapper;
import org.example.goshop.merchant.dto.MerchantProfileResponse;
import org.example.goshop.merchant.service.MerchantService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 商家智能导购助手配置 Service。
 */
@Service
@RequiredArgsConstructor
public class MerchantAiAssistantService {

    private final MerchantService merchantService;
    private final MerchantAiAssistantMapper assistantMapper;

    /**
     * 查询当前登录商家的助手配置。
     *
     * <p>userId 只能来自商家 JWT principal。先通过 MerchantService 恢复
     * 当前启用商家，再使用其 merchantId 查询配置，接口不接受任何
     * merchantId 参数，因此无法借此读取其他店铺的助手配置。</p>
     *
     * <p>没有配置时返回默认预览而不插入数据库。这样 GET 保持只读，
     * 后续真正保存配置时再由修改接口创建记录。</p>
     */
    public MerchantAiAssistantResponse getCurrentAssistant(
            Long merchantUserId
    ) {
        MerchantProfileResponse merchant =
                merchantService
                        .getCurrentMerchantProfile(
                                merchantUserId
                        );

        MerchantAiAssistant assistant =
                assistantMapper.selectByMerchantId(
                        merchant.id()
                );

        if (assistant == null) {
            return MerchantAiAssistantResponse.preview(
                    merchant
            );
        }

        return MerchantAiAssistantResponse.configured(
                assistant,
                merchant
        );
    }

    /**
     * 创建或完整更新当前登录商家的助手配置。
     *
     * <p>merchantId 由 JWT 对应的启用商家恢复，不使用请求参数。
     * 数据库唯一键和原子 Upsert 共同保证一个商家只有一条配置。</p>
     *
     * <p>名称和欢迎语去除首尾空白；空头像统一保存为 null，响应时
     * 自动回退到店铺 Logo。模型供应商、密钥和系统提示词始终由平台
     * 管理，不进入本请求。</p>
     */
    @Transactional
    public MerchantAiAssistantResponse saveCurrentAssistant(
            Long merchantUserId,
            SaveMerchantAiAssistantRequest request
    ) {
        MerchantProfileResponse merchant =
                merchantService
                        .getCurrentMerchantProfile(
                                merchantUserId
                        );

        MerchantAiAssistant assistant =
                new MerchantAiAssistant();
        /* 自定义 Upsert 不触发 MyBatis-Plus 的 ASSIGN_ID，需显式生成。 */
        assistant.setId(IdWorker.getId());
        assistant.setMerchantId(merchant.id());
        assistant.setName(request.name().trim());
        assistant.setAvatarUrl(
                StringUtils.hasText(request.avatarUrl())
                        ? request.avatarUrl().trim()
                        : null
        );
        assistant.setWelcomeMessage(
                request.welcomeMessage().trim()
        );
        assistant.setEnabled(
                Boolean.TRUE.equals(request.enabled())
                        ? 1
                        : 0
        );

        assistantMapper.upsert(assistant);

        /*
         * 更新场景必须返回数据库中原有的 ID；同时读取数据库生成的
         * created_at / updated_at，避免响应与最终持久化状态不一致。
         */
        MerchantAiAssistant saved =
                assistantMapper.selectByMerchantId(
                        merchant.id()
                );
        if (saved == null) {
            throw new BusinessException(
                    50000,
                    "助手配置保存失败，请稍后重试"
            );
        }

        return MerchantAiAssistantResponse.configured(
                saved,
                merchant
        );
    }
}
