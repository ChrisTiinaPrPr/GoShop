package org.example.goshop.agent.tool.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 读取并查询版本化商城规则。
 *
 * <p>该 Service 是 get_mall_policy 工具背后的事实来源。
 * 模型不能自己总结代码中的支付、发货和退款逻辑，只能读取这里
 * 返回的受控结构化内容。</p>
 *
 * <p>首期不需要 RAG：商城规则数量少、变化频率低，而且必须精确。
 * 将规则作为版本控制资源，比向量相似度检索更加稳定。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "goshop.agent",
        name = "enabled",
        havingValue = "true"
)
public class MallPolicyCatalogService {

    /** 版本控制规则文件在 classpath 中的位置。 */
    private static final String POLICY_RESOURCE = "agent/mall-policies.json";

    /** 首期必须存在的三个规则主题。 */
    private static final EnumSet<MallPolicyTopic>
            REQUIRED_TOPICS = EnumSet.of(
            MallPolicyTopic.PAYMENT,
            MallPolicyTopic.SHIPPING,
            MallPolicyTopic.REFUND
    );

    private final String version;
    private final LocalDate effectiveAt;

    /**
     * 启动时构建的只读规则索引。
     *
     * <p>规则不会在一次应用运行期间变化，因此不需要每次 Tool 调用
     * 都重新读取磁盘，也不需要访问数据库。</p>
     */
    private final Map<MallPolicyTopic, MallPolicyItem> policyIndex;

    /**
     * 应用启动时读取并校验规则。
     *
     * <p>如果规则文件损坏，应直接让 Agent 启动失败，而不是把不完整
     * 或错误的商城规则交给模型。</p>
     */
    public MallPolicyCatalogService(
            ObjectMapper objectMapper
    ) {
        Objects.requireNonNull(
                objectMapper,
                "objectMapper 不能为空"
        );

        MallPolicyResult document =
                readDocument(objectMapper);

        this.version = requireText(
                document.version(),
                "商城规则 version 不能为空"
        );

        this.effectiveAt = Objects.requireNonNull(
                document.effectiveAt(),
                "商城规则 effectiveAt 不能为空"
        );

        this.policyIndex =
                buildPolicyIndex(document.policies());
    }

    /**
     * 按主题查询商城规则。
     *
     * @param topic PAYMENT、SHIPPING、REFUND 或 ALL
     * @return 带版本信息的结构化规则
     */
    public MallPolicyResult getPolicy(
            MallPolicyTopic topic
    ) {
        Objects.requireNonNull(
                topic,
                "商城规则主题不能为空"
        );

        if (topic == MallPolicyTopic.ALL) {
            /*
             * EnumMap 按枚举声明顺序遍历，因此返回顺序稳定，
             * 方便模型理解，也方便自动化测试。
             */
            return new MallPolicyResult(
                    version,
                    effectiveAt,
                    List.copyOf(policyIndex.values())
            );
        }

        MallPolicyItem item = policyIndex.get(topic);

        if (item == null) {
            /*
             * 正常情况下启动校验已经保证主题存在。
             * 出现该异常说明规则文件或代码版本不一致，
             * 不能让模型自行编造一个答案。
             */
            throw new IllegalStateException(
                    "商城规则不存在，topic=" + topic
            );
        }

        return new MallPolicyResult(
                version,
                effectiveAt,
                List.of(item)
        );
    }

    /**
     * 从 classpath 读取 JSON。
     */
    private MallPolicyResult readDocument(
            ObjectMapper objectMapper
    ) {
        ClassPathResource resource =
                new ClassPathResource(POLICY_RESOURCE);

        if (!resource.exists()) {
            throw new IllegalStateException(
                    "找不到商城规则文件："
                            + POLICY_RESOURCE
            );
        }

        try (InputStream inputStream =
                     resource.getInputStream()) {

            return objectMapper.readValue(
                    inputStream,
                    MallPolicyResult.class
            );
        } catch (IOException exception) {
            /*
             * 不把整个 JSON 或敏感配置写入异常消息。
             * 原异常作为 cause 保留，方便开发环境定位具体格式错误。
             */
            throw new IllegalStateException(
                    "读取商城规则文件失败",
                    exception
            );
        }
    }

    /**
     * 校验规则并构建不可修改索引。
     */
    private Map<MallPolicyTopic, MallPolicyItem> buildPolicyIndex(List<MallPolicyItem> policies) {
        if (policies == null || policies.isEmpty()) {
            throw new IllegalStateException(
                    "商城规则 policies 不能为空"
            );
        }

        EnumMap<MallPolicyTopic, MallPolicyItem> index =
                new EnumMap<>(MallPolicyTopic.class);

        for (MallPolicyItem policy : policies) {
            MallPolicyItem normalized =
                    validateAndNormalize(policy);

            // put 后返回相同 Key 之前对应的值
            MallPolicyItem previous = index.put(
                    normalized.topic(),
                    normalized
            );

            if (previous != null) {
                throw new IllegalStateException(
                        "商城规则主题重复："
                                + normalized.topic()
                );
            }
        }

        // REQUIRED_TOPICS 记录了所有规则的 TOPIC
        if (!index.keySet().containsAll(REQUIRED_TOPICS)) {
            EnumSet<MallPolicyTopic> missing =
                    EnumSet.copyOf(REQUIRED_TOPICS);

            missing.removeAll(index.keySet());

            throw new IllegalStateException(
                    "商城规则缺少主题：" + missing
            );
        }

        /*
         * 返回不可修改的 Map 防止后续代码意外修改规则索引。
         */
        return Collections.unmodifiableMap(index);
    }

    /**
     * 校验单个规则，并把集合复制成不可修改集合。
     */
    private MallPolicyItem validateAndNormalize(
            MallPolicyItem policy
    ) {
        if (policy == null) {
            throw new IllegalStateException(
                    "商城规则条目不能为 null"
            );
        }

        MallPolicyTopic topic =
                Objects.requireNonNull(
                        policy.topic(),
                        "商城规则 topic 不能为空"
                );

        if (topic == MallPolicyTopic.ALL) {
            throw new IllegalStateException(
                    "规则文件不能声明 ALL 主题"
            );
        }

        return new MallPolicyItem(
                topic,
                requireText(
                        policy.title(),
                        topic + " title 不能为空"
                ),
                requireText(
                        policy.summary(),
                        topic + " summary 不能为空"
                ),
                normalizeTextList(
                        policy.rules(),
                        topic + " rules"
                ),
                normalizeTextList(
                        policy.limitations(),
                        topic + " limitations"
                )
        );
    }

    /**
     * 校验一个字符串列表是否合法，并清理每个字符串的首尾空白，最后返回一个不能被修改的新列表。
     */
    private List<String> normalizeTextList(
            List<String> values,
            String fieldName
    ) {
        if (values == null || values.isEmpty()) {
            throw new IllegalStateException(
                    fieldName + " 不能为空"
            );
        }

        return values.stream()
                .map(value -> requireText(
                        value,
                        fieldName + " 不能包含空字符串"
                ))
                .toList();
    }

    /**
     * 拒绝 null、空串和纯空白内容。
     */
    private String requireText(
            String value,
            String errorMessage
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    errorMessage
            );
        }

        return value.strip();
    }

}
