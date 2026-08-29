package org.example.goshop.wallet.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.goshop.wallet.entity.UserWallet;

@Mapper
public interface UserWalletMapper extends BaseMapper<UserWallet>{

    /**
     * 对钱包记录加排他锁
     * 必须在事务中调用，事务提交或回滚后锁才会释放
     */

    @Select("""
            SELECT *
            FROM user_wallet
            WHERE user_id = #{userId}
            LIMIT 1
            FOR UPDATE
            """)
    UserWallet selectByUserIdForUpdate(@Param("userId") Long userId);

    /**
     * 条件扣减余额。
     * 即使调用方已经加锁，仍保留 balance_cent >= amountCent 条件,作为防止余额变成负数的第二层保护.
     */
    @Update("""
            UPDATE user_wallet
            SET balance_cent = balance_cent - #{amountCent},
                updated_at = CURRENT_TIMESTAMP
            WHERE user_id = #{userId}
            AND balance_cent >= #{amountCent}
            """)
    int deductBalance(@Param("userId") Long userId, @Param("amountCent") Long amountCent);
}
