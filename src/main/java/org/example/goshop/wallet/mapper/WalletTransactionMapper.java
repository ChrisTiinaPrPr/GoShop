package org.example.goshop.wallet.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.goshop.wallet.entity.WalletTransaction;

@Mapper
public interface WalletTransactionMapper extends BaseMapper<WalletTransaction> {
}
