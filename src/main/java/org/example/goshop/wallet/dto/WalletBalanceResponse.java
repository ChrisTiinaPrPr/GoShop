package org.example.goshop.wallet.dto;

/**
 * 钱包余额响应。
 *
 * @param balanceCent 可用余额，单位：分
 */
public record WalletBalanceResponse(Long balanceCent) {
}
