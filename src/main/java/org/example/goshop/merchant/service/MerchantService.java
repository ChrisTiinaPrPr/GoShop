package org.example.goshop.merchant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.example.goshop.auth.entity.SysUser;
import org.example.goshop.auth.service.AuthService;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.merchant.dto.MerchantProfileResponse;
import org.example.goshop.merchant.dto.MerchantRegisterRequest;
import org.example.goshop.merchant.dto.MerchantRegisterResponse;
import org.example.goshop.merchant.entity.Merchant;
import org.example.goshop.merchant.mapper.MerchantMapper;
import org.example.goshop.oss.OssStorageService;
import org.example.goshop.oss.OssUploadResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.example.goshop.wallet.service.WalletService;
import org.example.goshop.merchant.dto.UpdateMerchantProfileRequest;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class MerchantService {

    private final MerchantMapper merchantMapper;
    private final OssStorageService ossStorageService;
    private final AuthService authService;
    private final WalletService walletService;

    public MerchantProfileResponse getMerchantProfile(Long merchantId) {
        Merchant merchant = requireEnabledMerchant(
                merchantId
        );

        return MerchantProfileResponse.from(merchant);
    }

    /**
     * 查询一个可以对买家公开展示的启用商家。
     *
     * <p>店铺资料、店铺商品列表以及后续商家导购助手都应复用该方法，
     * 避免不同公开入口对停用商家的判断不一致。</p>
     *
     * <p>不存在和已停用返回相同错误，防止公开接口被用于探测停用商家。</p>
     */
    public Merchant requireEnabledMerchant(
            Long merchantId
    ) {
        if (merchantId == null || merchantId <= 0) {
            throw new BusinessException(
                    40001,
                    "商家ID必须为正数"
            );
        }

        Merchant merchant = merchantMapper.selectOne(
                new LambdaQueryWrapper<Merchant>()
                        .eq(Merchant::getId, merchantId)
                        .eq(Merchant::getStatus, 1)
        );

        if (merchant == null) {
            throw new BusinessException(
                    40401,
                    "商家不存在或已停用"
            );
        }

        return merchant;
    }

    public MerchantProfileResponse getCurrentMerchantProfile(Long userId) {
        return MerchantProfileResponse.from(getCurrentEnabledMerchant(userId));
    }

    /** 更新当前登录商家的资料，Logo 只在数据库事务提交后删除旧对象。 */
    @Transactional
    public MerchantProfileResponse updateCurrentMerchantProfile(
            Long userId,
            UpdateMerchantProfileRequest request
    ) {
        Merchant merchant = getCurrentEnabledMerchant(userId);
        if (request.getName() == null && request.getDescription() == null && request.getLogo() == null) {
            throw new BusinessException(40001, "请至少修改一个字段");
        }
        if (request.getName() != null) {
            if (!StringUtils.hasText(request.getName())) {
                throw new BusinessException(40001, "店铺名称不能为空");
            }
            merchant.setName(request.getName().trim());
        }
        if (request.getDescription() != null) {
            merchant.setDescription(StringUtils.hasText(request.getDescription())
                    ? request.getDescription().trim() : null);
        }

        String oldLogoKey = merchant.getLogoObjectKey();
        String newLogoKey = null;
        try {
            if (request.getLogo() != null && !request.getLogo().isEmpty()) {
                OssUploadResult logo = ossStorageService.uploadMerchantLogo(userId, request.getLogo());
                newLogoKey = logo.objectKey();
                merchant.setLogoObjectKey(logo.objectKey());
                merchant.setLogoUrl(logo.url());
            }
            merchantMapper.updateById(merchant);

            if (newLogoKey != null && StringUtils.hasText(oldLogoKey)) {
                String keyToDelete = oldLogoKey;
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        ossStorageService.deleteQuietly(keyToDelete);
                    }
                });
            }
            return MerchantProfileResponse.from(merchant);
        } catch (RuntimeException exception) {
            ossStorageService.deleteQuietly(newLogoKey);
            throw exception;
        }
    }

    private Merchant getCurrentEnabledMerchant(Long userId) {
        Merchant merchant = merchantMapper.selectOne(
                new LambdaQueryWrapper<Merchant>()
                        .eq(Merchant::getUserId, userId)
                        .eq(Merchant::getStatus, 1)
        );
        if (merchant == null) {
            throw new BusinessException(40301, "商家不存在或已停用");
        }
        return merchant;
    }

    @Transactional
    public MerchantRegisterResponse registerMerchant(MerchantRegisterRequest request) {
        // 商家注册使用独立验证码场景，避免买家登录验证码被跨端复用。
        authService.consumeCode(request.getPhone(), request.getCode(), "MERCHANT_REGISTER");
        SysUser user = authService.findOrCreateUser(request.getPhone());
        if(!Integer.valueOf(1).equals(user.getStatus())) {
            throw new BusinessException(40301, "该账号已被禁用");
        }

        Merchant existingMerchant = merchantMapper.selectOne(
                new LambdaQueryWrapper<Merchant>()
                        .eq(Merchant::getUserId, user.getId())
        );
        if (existingMerchant != null) {
            throw new BusinessException(40901, "该账号已注册为商家");
        }

        String uploadedObjectKey = null;
        try {
            // 先上传 OSS， 数据库写入失败时在 catch 中清理该对象
            OssUploadResult logo = ossStorageService.uploadMerchantLogo(user.getId(), request.getLogo());
            uploadedObjectKey = logo.objectKey();

            Merchant merchant = new Merchant();
            merchant.setUserId(user.getId());
            merchant.setName(request.getName().trim());
            merchant.setDescription(
                    request.getDescription() == null ? null : request.getDescription().trim()
            );
            merchant.setLogoObjectKey(logo.objectKey());
            merchant.setLogoUrl(logo.url());
            merchant.setStatus(1);

            merchantMapper.insert(merchant);

            // 双角色模型只增加 MERCHANT，不移除 USER；两个门户分别签发 Token。
            authService.ensureRole(user.getId(), "USER");
            authService.ensureRole(user.getId(), "MERCHANT");
            walletService.ensureWallet(user.getId());
            String token = authService.issueToken(user, "MERCHANT", merchant.getId());
            return MerchantRegisterResponse.from(merchant, token);
        } catch (DuplicateKeyException exception) {
            ossStorageService.deleteQuietly(uploadedObjectKey);
            throw new BusinessException(40901, "该账号已注册为商家");
        } catch (RuntimeException exception) {
            // 数据库事务回滚时，避免遗留无引用的 OSS Logo 文件
            ossStorageService.deleteQuietly(uploadedObjectKey);
            throw exception;
        }
    }
}
