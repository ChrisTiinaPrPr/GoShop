package org.example.goshop.wallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.apache.catalina.User;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.wallet.dto.WalletBalanceResponse;
import org.example.goshop.wallet.entity.UserWallet;
import org.example.goshop.wallet.entity.WalletTransaction;
import org.example.goshop.wallet.mapper.UserWalletMapper;
import org.example.goshop.wallet.mapper.WalletTransactionMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.IllegalFormatCodePointException;

@Service
@RequiredArgsConstructor
public class WalletService {

    private final UserWalletMapper userWalletMapper;
    private final WalletTransactionMapper walletTransactionMapper;

    /**
     * 确保用户拥有钱包。
     *
     * <p>唯一索引 uk_user_wallet_user_id 会处理并发创建问题。
     * 新钱包默认余额为 0，不能在注册时赠送生产环境资金。</p>
     */
    @Transactional
    public void ensureWallet(Long userId) {
        Long count = userWalletMapper.selectCount(
                Wrappers.<UserWallet>lambdaQuery()
                        .eq(UserWallet::getUserId, userId)
        );

        if (count > 0) {
            return;
        }

        UserWallet wallet = new UserWallet();
        wallet.setUserId(userId);
        wallet.setBalanceCent(0L);

        try {
            userWalletMapper.insert(wallet);
        } catch (DuplicateKeyException ignored) {
            // 两个并发请求同时创建钱包时，其中一个会触发唯一键冲突。
            // 此时另一请求已经创建成功，可直接按幂等成功处理。
        }
    }

    /**
     * 查询当前可用余额
     */
    @Transactional
    public WalletBalanceResponse getBalance(Long userId) {
        // 兼容余额功能上线前已经存在的历史用户.
        ensureWallet(userId);

        UserWallet wallet = userWalletMapper.selectOne(
                new LambdaQueryWrapper<UserWallet>()
                        .eq(UserWallet::getUserId, userId)
        );

        if (wallet == null) {
            throw new BusinessException(50000, "钱包初始化失败");
        }

        return new WalletBalanceResponse(wallet.getBalanceCent());
    }

    /**
     * 扣除订单支付金额并记录资金流水
     * 该方法会加入 PaymentService 外层事务。后续订单或支付单更新失败时，
     * 余额扣减和钱包流水也会一起回滚。
     */
    @Transactional
    public void deductForPayment(
            Long userId,
            String paymentNo,
            Long amountCent
    ) {
        if (amountCent == null || amountCent <= 0) {
            throw new BusinessException(42201, "支付金额必须大于 0 ");
        }

        ensureWallet(userId);

        // 锁定钱包，防止两个订单同时读取到相同金额并重复扣款
        UserWallet wallet = userWalletMapper.selectByUserIdForUpdate(userId);
        if (wallet == null) {
            throw new BusinessException(50000, "钱包账户不存在");
        }

        long balanceBefore = wallet.getBalanceCent();
        if (balanceBefore < amountCent) {
            throw new BusinessException(42201, "账户余额不足");
        }
        
        int affectedRows = userWalletMapper.deductBalance(userId,amountCent);
        if (affectedRows != 1) {
            throw new BusinessException(40901, "交易异常，请刷新后重试");
        }

        long balanceAfter = balanceBefore - amountCent;

        WalletTransaction transaction = new WalletTransaction();
        transaction.setTransactionNo(IdWorker.getIdStr());
        transaction.setUserId(userId);
        transaction.setBizType("ORDER_PAYMENT");
        transaction.setBizNo(paymentNo);
        transaction.setDirection("OUT");
        transaction.setAmountCent(amountCent);
        transaction.setBalanceBeforeCent(balanceBefore);
        transaction.setBalanceAfterCent(balanceAfter);
        transaction.setRemark("余额支付订单");


        // biz_type | biz_no 有唯一约束，可以防止同一支付单重复写流水
        walletTransactionMapper.insert(transaction);
    }

    /**
     * 余额原路退款。biz_type + biz_no 唯一约束确保同一退款单只能入账一次。
     */
    @Transactional
    public void creditForRefund(Long userId, String refundNo, Long amountCent) {
        if (amountCent == null || amountCent <= 0) {
            throw new BusinessException(42201, "退款金额必须大于0");
        }
        ensureWallet(userId);
        UserWallet wallet = userWalletMapper.selectByUserIdForUpdate(userId);
        if (wallet == null) throw new BusinessException(50000, "钱包账户不存在");

        long before = wallet.getBalanceCent();
        final long after;
        try {
            after = Math.addExact(before, amountCent);
        } catch (ArithmeticException exception) {
            throw new BusinessException(50000, "钱包余额超出允许范围");
        }
        wallet.setBalanceCent(after);
        userWalletMapper.updateById(wallet);

        WalletTransaction transaction = new WalletTransaction();
        transaction.setTransactionNo(IdWorker.getIdStr());
        transaction.setUserId(userId);
        transaction.setBizType("ORDER_REFUND");
        transaction.setBizNo(refundNo);
        transaction.setDirection("IN");
        transaction.setAmountCent(amountCent);
        transaction.setBalanceBeforeCent(before);
        transaction.setBalanceAfterCent(after);
        transaction.setRemark("订单余额退款");
        walletTransactionMapper.insert(transaction);
    }
}
