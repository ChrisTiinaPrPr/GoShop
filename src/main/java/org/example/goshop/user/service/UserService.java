package org.example.goshop.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.example.goshop.auth.entity.SysUser;
import org.example.goshop.auth.mapper.SysUserMapper;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.oss.OssStorageService;
import org.example.goshop.oss.OssUploadResult;
import org.example.goshop.user.dto.*;
import org.example.goshop.user.entity.UserAddress;
import org.example.goshop.user.mapper.UserAddressMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    private final SysUserMapper sysUserMapper;
    private final OssStorageService ossStorageService;
    private final UserAddressMapper userAddressMapper;

    public UserProfileResponse getCurrentUserProfile(Long userId) {
        SysUser user = sysUserMapper.selectById(userId);

        if (user == null) {
            throw new BusinessException(40401,"用户不存在");
        }

        if (user.getStatus() != 1) {
            throw new BusinessException(40301, "该账号已被禁用");
        }

        return UserProfileResponse.from(user);
    }

    public UserProfileResponse updateCurrentUser(
            Long userId,
            UpdateProfileRequest request
    ) {
        SysUser user = sysUserMapper.selectById(userId);

        if (user == null) {
            throw new BusinessException(40401,"用户不存在");
        }

        if (user.getStatus() != 1) {
            throw new BusinessException(40301, "该账号已被禁用");
        }

        if (StringUtils.hasText(request.getNickName())) {
            user.setNickname(request.getNickName().trim());
        }

        String oldObjectKey = user.getAvatarObjectKey();
        boolean uploadedNewAvatar = request.getAvatar() != null && !request.getAvatar().isEmpty();

        if (uploadedNewAvatar) {
            OssUploadResult result = ossStorageService.uploadAvatar(
                    userId,
                    request.getAvatar()
            );

            user.setAvatarObjectKey(result.objectKey());
            user.setAvatarUrl(result.url());
        }

        sysUserMapper.updateById(user);

        // 数据库更新完成后才删除旧头像，避免更新失败导致旧头像丢失

        if (uploadedNewAvatar) {
            ossStorageService.deleteQuietly(oldObjectKey);
        }
        return UserProfileResponse.from(user);
    }

    /**
     * 查询当前登录用户的地址列表。
     * 不接收前端的userId参数，防止越权读取其他人的地址。UserId 从 JWT 中获取。
     */
    public List<AddressResponse> listCurrentUserAddresses(Long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(40401,"用户不存在");
        }

        if (user.getStatus() != 1) {
            throw new BusinessException(40301, "该账号已被禁用");
        }

        return userAddressMapper.selectList(
                new LambdaQueryWrapper<UserAddress>()
                        .eq(UserAddress::getUserId, userId)
                        // 默认地址优先；同一优先级按创建时间倒序
                        .orderByDesc(UserAddress::getIsDefault)
                        .orderByDesc(UserAddress::getCreatedAt)
        )
                .stream()
                .map(AddressResponse::from)
                .toList();
    }

    @Transactional
    public AddressResponse createCurrentUserAddress(
            Long userId,
            CreateAddressRequest request
    ) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(40401,"用户不存在");
        }
        if (user.getStatus() != 1) {
            throw new BusinessException(40301, "该账号已被禁用");
        }

        long addressCount = userAddressMapper.selectCount(
                new LambdaQueryWrapper<UserAddress>()
                        .eq(UserAddress::getUserId, userId)
        );

        // 规定每个用户最多 20 个地址
        if (addressCount >= 20) {
            throw new BusinessException(42201, "地址数量已达上限");
        }

        // 第一条地址必须为默认地址；沟洫地址由前端 isDefault 决定。
        boolean shouldBeDefault = addressCount == 0 || Boolean.TRUE.equals(request.isDefault());

        if (shouldBeDefault) {
            // 先取消旧默认地址，再插入新默认地址
            // 事务确保数据一致性
            userAddressMapper.update(
                    // null 表示不从实体对象取更新字段
                    null,
                    new LambdaUpdateWrapper<UserAddress>()
                            .eq(UserAddress::getUserId, userId)
                            .eq(UserAddress::getIsDefault, 1)
                            .set(UserAddress::getIsDefault, 0)
            );
        }

        UserAddress address = new UserAddress();
        address.setUserId(userId); // 不信任前端，从 JWT 中获取
        address.setReceiver(request.receiver().trim());
        address.setPhone(request.phone().trim());
        address.setProvince(request.province().trim());
        address.setCity(request.city().trim());
        address.setDistrict(request.district().trim());
        address.setDetail(request.detail().trim());
        address.setIsDefault(shouldBeDefault ? 1 : 0);

        userAddressMapper.insert(address);
        return AddressResponse.from(address);
    }
    @Transactional
    public AddressResponse updateCurrentUserAddress(
            Long userId,
            Long addressId,
            UpdateAddressRequest request
    ) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(40401,"用户不存在");
        }
        if (user.getStatus() != 1) {
            throw new BusinessException(40301, "该账号已被禁用");
        }

        // 查询条件同时包含地址 ID 与当前用户 ID， 杜绝越权修改。
        UserAddress address = userAddressMapper.selectOne(
                new LambdaQueryWrapper<UserAddress>()
                        .eq(UserAddress::getId, addressId)
                        .eq(UserAddress::getUserId, userId)
        );

        // 不区分“地址不存在”和“不是自己的地址”，避免泄露其他用户的地址 ID。
        if (address == null) {
            throw new BusinessException(40401,"地址不存在");
        }

        address.setReceiver(request.receiver().trim());
        address.setPhone(request.phone().trim());
        address.setProvince(request.province().trim());
        address.setCity(request.city().trim());
        address.setDistrict(request.district().trim());
        address.setDetail(request.detail().trim());

        // 不修改 isDefault，仅保留默认状态。
        userAddressMapper.updateById(address);
        return AddressResponse.from(address);
    }

    @Transactional
    public void deleteCurrentUserAddress(Long userId, Long addressId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(40401,"用户不存在");
        }
        if (user.getStatus() != 1) {
            throw new BusinessException(40301, "该账号已被禁用");
        }

        // 同时校验地址归属，避免越权删除其他用户的地址。
        UserAddress address = userAddressMapper.selectOne(
                new LambdaQueryWrapper<UserAddress>()
                        .eq(UserAddress::getId, addressId)
                        .eq(UserAddress::getUserId, userId)
        );

        if (address == null) {
            throw new BusinessException(40401,"地址不存在");
        }

        boolean deletedDefaultAddress = Integer.valueOf(1).equals(address.getIsDefault());

        // 先执行删除操作
        userAddressMapper.deleteById(address.getId());

        // 若删除的是默认地址，则把剩余地址中最新的一条设为默认地址。
        if (deletedDefaultAddress) {
            UserAddress replacement = userAddressMapper.selectOne(
                    new LambdaQueryWrapper<UserAddress>()
                            .eq(UserAddress::getUserId, userId)
                            .orderByDesc(UserAddress::getCreatedAt)
                            .orderByDesc(UserAddress::getId)
                            .last("LIMIT 1")
            );

            if (replacement != null) {
                userAddressMapper.update(
                        null,
                        new LambdaUpdateWrapper<UserAddress>()
                                .eq(UserAddress::getId, replacement.getId())
                                .set(UserAddress::getIsDefault, 1)
                );
            }
        }
    }

    @Transactional
    public AddressResponse setCurrentUserDefaultAddress(Long userId, Long addressId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(40401,"用户不存在");
        }
        if (user.getStatus() != 1) {
            throw new BusinessException(40301, "该账号已被禁用");
        }

        // 同时按地址 ID 和 当前用户 ID 查询，避免越权设置其他用户的地址
        UserAddress address = userAddressMapper.selectOne(
                new LambdaQueryWrapper<UserAddress>()
                        .eq(UserAddress::getId, addressId)
                        .eq(UserAddress::getUserId, userId)
        );

        if (address == null) {
            throw new BusinessException(40401,"地址不存在");
        }

        // 先取消该用户的默认地址
        // 清空当前用户所有地址的默认状态，旧默认地址会变为 0
        userAddressMapper.update(
                null,
                new LambdaUpdateWrapper<UserAddress>()
                        .eq(UserAddress::getUserId, userId)
                        .set(UserAddress::getIsDefault, 0)
        );

        userAddressMapper.update(
                null,
                new LambdaUpdateWrapper<UserAddress>()
                        .eq(UserAddress::getId, addressId)
                        .eq(UserAddress::getUserId, userId)
                        .set(UserAddress::getIsDefault, 1)
        );

        address.setIsDefault(1);
        return AddressResponse.from(address);
    }
}
