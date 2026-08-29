package org.example.goshop.merchant.ai.controller;

import org.example.goshop.merchant.ai.dto.BuyerMerchantAiQuestionRequest;
import org.example.goshop.merchant.ai.dto.BuyerMerchantAiStreamEvent;
import org.example.goshop.merchant.ai.service.BuyerMerchantAiAnswerService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证店铺智能导购 Controller 使用真实 SSE 帧输出 Flux 事件。 */
class BuyerMerchantAiControllerSseTest {

    @Test
    void shouldEncodeEachAnswerEventAsSseDataFrame() throws Exception {
        BuyerMerchantAiAnswerService answerService =
                mock(BuyerMerchantAiAnswerService.class);
        when(answerService.streamAnswer(
                eq(1001L),
                eq(7001L),
                any(BuyerMerchantAiQuestionRequest.class)
        )).thenReturn(Flux.just(
                BuyerMerchantAiStreamEvent.started(
                        7001L,
                        8001L,
                        "星环导购",
                        null,
                        "M7鼠标多重"
                ),
                BuyerMerchantAiStreamEvent.textDelta("约 59 克"),
                BuyerMerchantAiStreamEvent.completed(true, List.of())
        ));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new BuyerMerchantAiController(answerService)
        ).build();
        UsernamePasswordAuthenticationToken buyerAuthentication =
                new UsernamePasswordAuthenticationToken(1001L, null);

        MvcResult asyncResult = mockMvc.perform(post(
                        "/api/v1/buyer/merchants/7001/ai-assistant/questions"
                )
                        /*
                         * standaloneSetup 不加载安全过滤链；principal 只用于
                         * 验证 Controller 把 JWT 已解析出的 Long 身份传给 Service。
                         */
                        .principal(buyerAuthentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"question\":\"M7鼠标多重\"}"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        /*
         * asyncDispatch 等待 Flux 完成后检查真实 MVC 编码结果，防止接口虽
         * 声明 SSE，却被消息转换器意外写成一个普通 JSON 数组。
         */
        mockMvc.perform(asyncDispatch(asyncResult))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.TEXT_EVENT_STREAM
                ))
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andExpect(content().string(containsString(
                        "data:{\"type\":\"STARTED\""
                )))
                .andExpect(content().string(containsString(
                        "data:{\"type\":\"TEXT_DELTA\""
                )))
                .andExpect(content().string(containsString(
                        "data:{\"type\":\"COMPLETED\""
                )));
    }
}
