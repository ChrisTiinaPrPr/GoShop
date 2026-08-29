package org.example.goshop.agent.tool.policy;

import java.time.LocalDate;
import java.util.List;

/**
 * 商城规则查询结果。
 *
 * @param version     规则版本，用于审计和排查规则变更
 * @param effectiveAt 当前规则生效日期
 * @param policies    本次查询命中的规则
 */
public record MallPolicyResult(
        String version,
        LocalDate effectiveAt,
        List<MallPolicyItem> policies
) {
}
