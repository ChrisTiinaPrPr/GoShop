package org.example.goshop.user.dto;

import org.example.goshop.user.entity.UserAddress;

public record AddressResponse(
        String id,
        String receiver,
        String phone,
        String province,
        String city,
        String district,
        String detail,
        Integer isDefault
) {
    public static AddressResponse from(UserAddress address) {
        return new AddressResponse(
                String.valueOf(address.getId()),
                address.getReceiver(),
                address.getPhone(),
                address.getProvince(),
                address.getCity(),
                address.getDistrict(),
                address.getDetail(),
                address.getIsDefault()
        );
    }
}
