package org.example.goshop.merchant.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.goshop.merchant.ai.entity.MerchantAiDocument;

/** 商家智能导购文档 Mapper。 */
@Mapper
public interface MerchantAiDocumentMapper
        extends BaseMapper<MerchantAiDocument> {

    /** 按当前商家与内容摘要查询，支持上传重试时内容级去重。 */
    @Select("""
        SELECT *
        FROM merchant_ai_document
        WHERE merchant_id = #{merchantId}
          AND sha256 = #{sha256}
        LIMIT 1
        """)
    MerchantAiDocument selectByMerchantAndSha256(
            @Param("merchantId") Long merchantId,
            @Param("sha256") String sha256
    );

    /** 统计助手已有文档数量，防止无限占用对象存储和向量资源。 */
    @Select("""
        SELECT COUNT(*)
        FROM merchant_ai_document
        WHERE assistant_id = #{assistantId}
        """)
    long countByAssistantId(
            @Param("assistantId") Long assistantId
    );

    /**
     * 分页查询当前商家的导购文档。
     *
     * <p>租户条件直接进入 SQL，不能只依赖 Service 内存过滤；相同毫秒
     * 上传的文档再按雪花 ID 倒序，保证翻页顺序稳定。</p>
     */
    @Select("""
        <script>
        SELECT *
        FROM merchant_ai_document
        WHERE merchant_id = #{merchantId}
        <if test="status != null">
          AND status = #{status}
        </if>
        ORDER BY created_at DESC, id DESC
        </script>
        """)
    IPage<MerchantAiDocument> selectDocumentPage(
            Page<MerchantAiDocument> page,
            @Param("merchantId") Long merchantId,
            @Param("status") String status
    );

    /**
     * 锁定当前商家拥有的文档，供删除流程校验状态并防止并发修改。
     * 其他商家的相同 documentId 不会命中。
     */
    @Select("""
        SELECT *
        FROM merchant_ai_document
        WHERE id = #{documentId}
          AND merchant_id = #{merchantId}
        FOR UPDATE
        """)
    MerchantAiDocument selectOwnedDocumentForUpdate(
            @Param("documentId") Long documentId,
            @Param("merchantId") Long merchantId
    );

    /** 处理线程按文档与商家双重条件读取任务快照。 */
    @Select("""
        SELECT *
        FROM merchant_ai_document
        WHERE id = #{documentId}
          AND merchant_id = #{merchantId}
        LIMIT 1
        """)
    MerchantAiDocument selectOwnedDocument(
            @Param("documentId") Long documentId,
            @Param("merchantId") Long merchantId
    );

    /**
     * 待处理、失败、需要重建索引的 READY 或已经超时的任务才可进入
     * PROCESSING。
     * staleBefore 由 Service 在持有行锁时判断后传入，SQL 条件负责并发兜底。
     *
     * <p>这是 Java 注解 SQL，不是 MyBatis XML 映射文件，因此比较运算符
     * 必须直接写成小于号；XML 实体会被原样发送给 MySQL 并引发语法错误。</p>
     */
    @Update("""
        UPDATE merchant_ai_document
        SET status = 'PROCESSING',
            failure_reason = NULL,
            chunk_count = 0,
            updated_at = #{updatedAt}
        WHERE id = #{documentId}
          AND merchant_id = #{merchantId}
          AND (
                status IN ('UPLOADED', 'FAILED', 'READY')
                OR (status = 'PROCESSING' AND updated_at < #{staleBefore})
              )
        """)
    int markProcessing(
            @Param("documentId") Long documentId,
            @Param("merchantId") Long merchantId,
            @Param("updatedAt") java.time.LocalDateTime updatedAt,
            @Param("staleBefore") java.time.LocalDateTime staleBefore
    );

    /** 向量与分片都成功后才把文档发布为 READY。 */
    @Update("""
        UPDATE merchant_ai_document
        SET status = 'READY',
            failure_reason = NULL,
            chunk_count = #{chunkCount},
            updated_at = #{updatedAt}
        WHERE id = #{documentId}
          AND merchant_id = #{merchantId}
          AND status = 'PROCESSING'
        """)
    int markReady(
            @Param("documentId") Long documentId,
            @Param("merchantId") Long merchantId,
            @Param("chunkCount") int chunkCount,
            @Param("updatedAt") java.time.LocalDateTime updatedAt
    );

    /** 仅允许当前处理任务记录安全、截断后的失败原因。 */
    @Update("""
        UPDATE merchant_ai_document
        SET status = 'FAILED',
            failure_reason = #{failureReason},
            chunk_count = 0,
            updated_at = #{updatedAt}
        WHERE id = #{documentId}
          AND merchant_id = #{merchantId}
          AND status = 'PROCESSING'
        """)
    int markFailed(
            @Param("documentId") Long documentId,
            @Param("merchantId") Long merchantId,
            @Param("failureReason") String failureReason,
            @Param("updatedAt") java.time.LocalDateTime updatedAt
    );

    /** 删除条件再次包含 merchant_id，形成数据库层租户隔离兜底。 */
    @Delete("""
        DELETE FROM merchant_ai_document
        WHERE id = #{documentId}
          AND merchant_id = #{merchantId}
        """)
    int deleteOwnedDocument(
            @Param("documentId") Long documentId,
            @Param("merchantId") Long merchantId
    );
}
