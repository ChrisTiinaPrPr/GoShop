package org.example.goshop.payment.service;

import com.alipay.api.AlipayApiException;
import com.alipay.api.internal.util.AlipaySignature;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.bouncycastle.pqc.crypto.newhope.NHSecretKeyProcessor;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.infrastructure.mq.outbox.MqOutboxService;
import org.example.goshop.order.entity.MallOrder;
import org.example.goshop.order.mapper.MallOrderMapper;
import org.example.goshop.payment.config.AlipayProperties;
import org.example.goshop.payment.dto.CreatePaymentRequest;
import org.example.goshop.payment.dto.CreatePaymentResponse;
import org.example.goshop.payment.entity.PaymentRecord;
import org.example.goshop.payment.mapper.PaymentRecordMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.example.goshop.payment.dto.PaymentChannel;
import org.example.goshop.wallet.service.WalletService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final MallOrderMapper mallOrderMapper;
    private final PaymentRecordMapper paymentRecordMapper;
    private final AlipayPaymentGateway alipayPaymentGateway;
    private final AlipayProperties alipayProperties;
    private final ObjectMapper objectMapper;
    private final WalletService walletService;
    private final MqOutboxService outboxService;

    @Transactional
    public CreatePaymentResponse createPayment(
            Long userId,
            String orderNo,
            CreatePaymentRequest request
    ) {
        /**
         * 锁定订单，而不是普通 selectOne
         * 同一个订单多个并发支付请求会在这里串行执行。
         */
        MallOrder order = mallOrderMapper.selectByOrderNoAndUserIdForUpdate(orderNo,userId);

        if (order == null) {
            throw new BusinessException(40401, "订单不存在");
        }

        if (!"PENDING_PAYMENT".equals(order.getStatus())) {
            throw new BusinessException(40901, "订单状态异常");
        }

        if (order.getExpireAt() == null || !order.getExpireAt().isAfter(LocalDateTime.now())) {
            throw new BusinessException(40901, "订单已过期");
        }

        if (order.getPayAmountCent() == null || order.getPayAmountCent() <= 0) {
            throw new BusinessException(42201,"订单支付金额异常");
        }

        PaymentRecord record = paymentRecordMapper.selectOne(
                new LambdaQueryWrapper<PaymentRecord>()
                        .eq(PaymentRecord::getOrderId,order.getId())
                        .eq(PaymentRecord::getChannel,request.channel().name())
        );

        if (record == null) {
            record = new PaymentRecord();
            //支付单号独立于商城单号，方便后续按支付渠道查询
            record.setPaymentNo(IdWorker.getIdStr());
            record.setOrderId(order.getId());
            record.setChannel(request.channel().name());
            record.setAmountCent(order.getPayAmountCent());
            record.setStatus("INIT");
            paymentRecordMapper.insert(record);
        }

        if ("PAID".equals(record.getStatus())) {
            throw new BusinessException(40901, "订单已支付");
        }

        if (request.channel() == PaymentChannel.BALANCE) {
            return payWithBalance(userId,order,record);
        }

        if (request.channel() == PaymentChannel.ALIPAY) {
            return alipayPaymentGateway.create(order,record);
        }
        throw new BusinessException(40001,"不支持的支付渠道");
    }

    /**
     * 使用钱包余额支付
     * <p>本方法运行在 CreatePayment 的事务中。任何一步失败，
     * 钱包、流水、支付单和订单都会整体回滚</p>
     */
    private CreatePaymentResponse payWithBalance(
            Long userId,
            MallOrder order,
            PaymentRecord paymentRecord
    ) {
        // 锁定钱包，扣减余额并写入不可变资金流水
        walletService.deductForPayment(userId,paymentRecord.getPaymentNo(),paymentRecord.getAmountCent());

        LocalDateTime paidAt = LocalDateTime.now();
        paymentRecord.setStatus("PAID");
        // 余额支付没有第三方流水号，生成内部唯一编号满足支付记录约束。
        paymentRecord.setThirdPartyNo("BALANCE-" + paymentRecord.getPaymentNo());
        paymentRecord.setPaidAt(paidAt);
        paymentRecordMapper.updateById(paymentRecord);

        // 余额扣款成功后，订单直接进入待发货状态，不需要异步回调.
        order.setStatus("WAITING_SHIPMENT");
        order.setPaidAt(paidAt);
        mallOrderMapper.updateById(order);

        /*
         * 支付结果和 ORDER_PAID Outbox 在同一个事务提交。
         *
         * 通知消费者失败只会影响通知，不会把已经成功的余额支付回滚。
         */
        outboxService.saveOrderPaid(
                order,
                paymentRecord.getPaymentNo()
        );
        return new CreatePaymentResponse(
                paymentRecord.getPaymentNo(),
                PaymentChannel.BALANCE,
                paymentRecord.getAmountCent(),
                null
        );
    }

    @Transactional
    public void handleAlipayCallback(Map<String,String> params) {

        boolean signatureValid;
        try {
            signatureValid = AlipaySignature.rsaCheckV1(
                    params,
                    alipayProperties.getPublicKey(),
                    "UTF-8",
                    "RSA2"
            );
        } catch (AlipayApiException exception) {
            throw new BusinessException(40001,"支付宝回调验签异常");
        }

        if (!signatureValid) {
            throw new BusinessException(40001,"支付宝回调验签失败");
        }

        String paymentNo = params.get("out_trade_no");
        String tradeStatus = params.get("trade_status");

        if (paymentNo == null || tradeStatus == null) {
            throw new BusinessException(40001,"支付宝回调参数异常");
        }

        // 只处理真正支付成功的通知，其他交易状态确认受到即可
        if (!"TRADE_SUCCESS".equals(tradeStatus) && !"TRADE_FINISHED".equals(tradeStatus)) {
            return;
        }

        /*
         * 第一次普通查询只为了获得 orderId，不持有行锁。
         */
        PaymentRecord paymentSnapshot = paymentRecordMapper.selectByPaymentNo(paymentNo);

        if (paymentSnapshot == null || !"ALIPAY".equals(paymentSnapshot.getChannel())) {
            throw new BusinessException(40401, "支付单不存在");
        }

        /*
         * 统一锁顺序：先订单、后支付单。
         * 这样与超时取消事务保持一致。
         */
        MallOrder order = mallOrderMapper.selectByIdForUpdate(paymentSnapshot.getOrderId());
        PaymentRecord paymentRecord = paymentRecordMapper.selectByPaymentNoForUpdate(paymentNo);
        if (order == null || paymentRecord == null) {
            throw new BusinessException(40401, "订单或支付单不存在");
        }

        /*
         * 支付宝会重复发送异步回调。
         * 如果支付单已经完成，直接成功返回，不再次生成 ORDER_PAID 事件。
         */
        if ("PAID".equals(paymentRecord.getStatus())) {
            return;
        }

        if (!"PENDING_PAYMENT".equals(order.getStatus())) {
            throw new BusinessException(40901, "订单状态异常");
        }

        long callbackAmountCent;
        try {
            // 支付宝金额单位为“元”，商城支付单金额单位为“分”。
            callbackAmountCent = new BigDecimal(params.get("total_amount")).movePointRight(2).longValueExact();
        } catch (RuntimeException exception) {
            throw new BusinessException(40001,"支付宝回调金额异常");
        }

        if (callbackAmountCent != paymentRecord.getAmountCent()) {
            throw new BusinessException(42201,"支付宝回调金额不一致");
        }

        if (!alipayProperties.getAppId().equals(params.get("app_id"))) {
            throw new BusinessException(40001,"支付宝回调 AppID 不一致");
        }

        if (!"PENDING_PAYMENT".equals(order.getStatus())) {
            throw new BusinessException(40901, "订单状态异常");
        }

        LocalDateTime paidAt = LocalDateTime.now();

        paymentRecord.setStatus("PAID");
        paymentRecord.setThirdPartyNo(params.get("trade_no"));
        paymentRecord.setPaidAt(paidAt);
        paymentRecord.setCallbackRaw(serializeCallbackParams(params));
        paymentRecordMapper.updateById(paymentRecord);

        order.setStatus("WAITING_SHIPMENT");
        order.setPaidAt(paidAt);
        mallOrderMapper.updateById(order);

        outboxService.saveOrderPaid(
                order,
                paymentRecord.getPaymentNo()
        );
    }

    /**
     * 将支付宝回调参数保存为 JSON，便于后续排查
     */
    private String serializeCallbackParams(Map<String,String> params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            // 回调原文无法保存时，不应把订单标记为已支付
            throw new BusinessException(50000, "支付回调参数序列化失败");
        }
    }
}
