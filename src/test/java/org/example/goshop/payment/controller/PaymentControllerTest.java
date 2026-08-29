package org.example.goshop.payment.controller;

import org.example.goshop.payment.config.AlipayProperties;
import org.example.goshop.payment.service.PaymentService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class PaymentControllerTest {

    /**
     * 回归截图中的 Whitelabel 404：支付宝同步回跳必须命中 GET 路由，
     * 并由后端明确重定向到实际存在的 Vue 订单页面。
     */
    @Test
    void shouldRedirectAlipayBrowserReturnToFrontendOrdersPage() throws Exception {
        AlipayProperties properties = new AlipayProperties();
        properties.setFrontendReturnUrl("http://localhost:5173/orders");

        PaymentController controller = new PaymentController(
                mock(PaymentService.class),
                properties
        );

        // 使用真实 MVC 路由匹配验证 GET 地址，避免只测方法而漏掉路径配置错误。
        standaloneSetup(controller)
                .build()
                .perform(get("/api/v1/payments/alipay/return"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:5173/orders"));
    }
}
