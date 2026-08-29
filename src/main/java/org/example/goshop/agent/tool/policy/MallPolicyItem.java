package org.example.goshop.agent.tool.policy;

import java.util.List;

/**
 * 单个主题的结构化商城规则。
 *
 * <p>不要把规则预先拼成一大段提示词。结构化结果更容易：</p>
 *
 * <ul>
 *     <li>被模型准确理解；</li>
 *     <li>被测试代码断言；</li>
 *     <li>在未来替换成数据库或 RAG 数据源；</li>
 *     <li>避免模型遗漏限制条件。</li>
 * </ul>
 */
public record MallPolicyItem(
        /** 规则主题。 */
        MallPolicyTopic topic,

        /** 给用户展示的规则标题。 */
        String title,

        /** 规则的简短概述。 */
        String summary,

        /** 当前商城已经实现并可以保证的规则。 */
        List<String> rules,

        /**
         * 当前版本的能力边界。
         *
         * <p>模型必须了解这些限制，避免编造发货时效、
         * 自动退款能力或其他尚未实现的功能。</p>
         */
        List<String> limitations
) {
}
