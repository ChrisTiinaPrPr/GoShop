package org.example.goshop.payment.service;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.response.AlipayTradePagePayResponse;
import lombok.RequiredArgsConstructor;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.order.entity.MallOrder;
import org.example.goshop.payment.config.AlipayProperties;
import org.example.goshop.payment.dto.CreatePaymentResponse;
import org.example.goshop.payment.dto.PaymentChannel;
import org.example.goshop.payment.entity.PaymentRecord;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class AlipayPaymentGateway {

    private final AlipayClient alipayClient;
    private final AlipayProperties properties;

    public CreatePaymentResponse create(MallOrder order, PaymentRecord paymentRecord) {
        try {
            AlipayTradePagePayModel model = new AlipayTradePagePayModel();

            // 第三方支付平台使用支付单号，不能直接使用商城订单号。
            model.setOutTradeNo(paymentRecord.getPaymentNo());

            // 数据库存储“分”，支付宝要求传“元”的字符串
            model.setTotalAmount(BigDecimal.valueOf(paymentRecord.getAmountCent(),2).toPlainString());
            model.setSubject("优购商城订单-" + order.getOrderNo());
            model.setProductCode("FAST_INSTANT_TRADE_PAY");

            AlipayTradePagePayRequest request = new AlipayTradePagePayRequest();
            request.setNotifyUrl(properties.getNotifyUrl());
            request.setReturnUrl(properties.getReturnUrl());
            request.setBizModel(model);

            // 返回自动提交的 HTML form，前端应新开页面后写入并提交该表单。
            AlipayTradePagePayResponse response = alipayClient.pageExecute(request,"POST");

            return new CreatePaymentResponse(
                    paymentRecord.getPaymentNo(),
                    PaymentChannel.ALIPAY,
                    paymentRecord.getAmountCent(),
                    response.getBody()
            );
        } catch (AlipayApiException exception) {
            throw new BusinessException(50000,"支付宝预下单失败");
        }
    }
}
