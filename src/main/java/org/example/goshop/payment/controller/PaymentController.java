package org.example.goshop.payment.controller;


import lombok.RequiredArgsConstructor;
import org.example.goshop.payment.config.AlipayProperties;
import org.example.goshop.payment.service.PaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final AlipayProperties alipayProperties;

    @PostMapping(
            value = "/alipay/callback",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE
    )
    public String alipayCallback(@RequestParam Map<String,String> params) {
        try {
            paymentService.handleAlipayCallback(params);

            // 支付宝收到 success 才会停止异步通知重试。
            return "success";
        } catch (Exception exception) {
            // 返回 fail 后支付宝会按策略重试，方便修复临时网络或服务异常。
            return "fail";
        }
    }

    /**
     * 接收支付完成后的浏览器同步回跳。
     *
     * <p>支付宝访问 return_url 时使用 GET；它与上面的服务器异步通知不是同一个接口。
     * 同步回跳参数可能被用户修改，因此这里只跳转到前端展示页面，绝不据此修改订单状态。
     * 订单是否支付成功仍然只能由已经验签的异步通知决定。</p>
     */
    @GetMapping("/alipay/return")
    public ResponseEntity<Void> alipayReturn() {
        return ResponseEntity
                .status(HttpStatus.FOUND)
                .location(URI.create(alipayProperties.getFrontendReturnUrl()))
                .build();
    }
}
