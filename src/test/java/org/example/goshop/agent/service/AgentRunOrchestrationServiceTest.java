package org.example.goshop.agent.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 确定性加购路由规则的纯单元测试。
 *
 * <p>这里不连接模型和数据库，只验证用户省略“加入购物车”时，主编排层
 * 能否结合上一轮助手正文进入服务端确定性工具链。</p>
 */
class AgentRunOrchestrationServiceTest {

    @Test
    void shouldRouteExplicitAddCartCommand() {
        List<Message> messages = conversation(
                "推荐星环 H1 无线游戏耳机。",
                "帮我把紫色的加入购物车"
        );

        assertTrue(shouldRoute(
                "帮我把紫色的加入购物车",
                messages
        ));

        assertTrue(shouldRoute(
                "行，加入购物车",
                messages
        ));

        assertTrue(shouldRoute(
                "这个帮我加购一下",
                messages
        ));
    }

    @Test
    void shouldRouteSkuSelectionWithoutRepeatingAddCartWords() {
        /*
         * 对应问题会话中的真实输入。上一轮已经展示了两个 SKU，用户只说
         * “我要紫色的”时，不能再交给模型自由决定是否调用工具。
         */
        List<Message> messages = conversation(
                "星环 H1 无线游戏耳机有深空黑和星云紫两个规格。",
                "我要紫色的"
        );

        assertTrue(shouldRoute("我要紫色的", messages));
        assertTrue(shouldRoute("我选星云紫款", messages));
        assertTrue(shouldRoute("就这个", messages));
    }

    @Test
    void shouldRouteRetryOnlyAfterAddCartContext() {
        List<Message> addCartMessages = conversation(
                "已为您准备待加购确认信息，请点击确认卡片。",
                "没有成功，你再试试"
        );

        assertTrue(shouldRoute(
                "没有成功，你再试试",
                addCartMessages
        ));

        assertTrue(shouldRoute(
                "再试试",
                addCartMessages
        ));

        List<Message> ordinaryMessages = conversation(
                "图片加载失败，请稍后再试。",
                "再试试"
        );

        assertFalse(shouldRoute(
                "再试试",
                ordinaryMessages
        ));
    }

    @Test
    void shouldRouteShortAffirmationOnlyAfterAddCartInvitation() {
        assertTrue(shouldRoute(
                "可以",
                conversation(
                        "需要帮您把这款键盘加入购物车吗？",
                        "可以"
                )
        ));

        assertFalse(shouldRoute(
                "可以",
                conversation(
                        "需要继续比较耳机的续航吗？",
                        "可以"
                )
        ));
    }

    @Test
    void shouldRouteBareSkuAnswerOnlyAfterAddCartQuestion() {
        /*
         * 对应实际截图：助手已经询问把哪一款加入购物车，用户只回复
         * “黑色款”。此时必须进入确定性工具链，不能交给模型猜 productId。
         */
        List<Message> addCartMessages = conversation(
                "两款价格一样，需要帮您把哪一款加入购物车呢？",
                "黑色款"
        );

        assertTrue(shouldRoute("黑色款", addCartMessages));
        assertTrue(shouldRoute("星云紫色", addCartMessages));
        assertTrue(shouldRoute("红轴版", addCartMessages));

        List<Message> ordinaryMessages = conversation(
                "这款商品有哪些颜色？",
                "黑色款"
        );

        assertFalse(shouldRoute("黑色款", ordinaryMessages));
    }

    @Test
    void shouldNotRouteGeneralQuestionOrSelectionWithoutHistory() {
        assertFalse(shouldRoute(
                "为什么这个商品无法加入购物车？",
                conversation(
                        "您可以检查库存状态。",
                        "为什么这个商品无法加入购物车？"
                )
        ));

        assertFalse(shouldRoute(
                "紫色的好看吗",
                conversation(
                        "这款耳机有紫色版本。",
                        "紫色的好看吗"
                )
        ));

        assertFalse(shouldRoute(
                "我要紫色的",
                List.of(new UserMessage("我要紫色的"))
        ));
    }

    private boolean shouldRoute(
            String userContent,
            List<Message> messages
    ) {
        return AgentRunOrchestrationService
                .shouldUseDeterministicAddCartFlow(
                        userContent,
                        messages
                );
    }

    private List<Message> conversation(
            String assistantContent,
            String currentUserContent
    ) {
        return List.of(
                new UserMessage("请推荐一款商品"),
                new AssistantMessage(assistantContent),
                new UserMessage(currentUserContent)
        );
    }
}
