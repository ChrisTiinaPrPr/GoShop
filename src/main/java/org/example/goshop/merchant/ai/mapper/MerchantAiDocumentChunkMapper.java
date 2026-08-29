package org.example.goshop.merchant.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.example.goshop.merchant.ai.entity.MerchantAiDocumentChunk;
import org.example.goshop.merchant.ai.entity.MerchantAiKnowledgeChunkRow;

import java.util.List;

/** 导购文档分片 Mapper。 */
@Mapper
public interface MerchantAiDocumentChunkMapper
        extends BaseMapper<MerchantAiDocumentChunk> {

    /** 重建文档索引前删除旧分片，数据库始终只保留最新解析结果。 */
    @Delete("""
        DELETE FROM merchant_ai_document_chunk
        WHERE document_id = #{documentId}
          AND merchant_id = #{merchantId}
        """)
    int deleteByOwnedDocument(
            @Param("documentId") Long documentId,
            @Param("merchantId") Long merchantId
    );

    /**
     * 对 Qdrant 候选点执行数据库事实源复核。
     *
     * <p>查询同时约束分片和文档两侧的 merchant_id、assistant_id，并只接受
     * READY 文档。即使向量删除延迟或索引中残留旧点，也不会跨店返回或把
     * 已失效文档交给后续大模型。</p>
     */
    @Select("""
        <script>
        SELECT c.vector_id,
               c.document_id,
               c.chunk_index,
               c.content,
               d.original_filename
        FROM merchant_ai_document_chunk c
        INNER JOIN merchant_ai_document d
                ON d.id = c.document_id
               AND d.merchant_id = c.merchant_id
               AND d.assistant_id = c.assistant_id
        WHERE c.merchant_id = #{merchantId}
          AND c.assistant_id = #{assistantId}
          AND d.merchant_id = #{merchantId}
          AND d.assistant_id = #{assistantId}
          AND d.status = 'READY'
          AND c.vector_id IN
          <foreach collection="vectorIds"
                   item="vectorId"
                   open="(" separator="," close=")">
              #{vectorId}
          </foreach>
        </script>
        """)
    List<MerchantAiKnowledgeChunkRow> selectReadyOwnedChunks(
            @Param("merchantId") Long merchantId,
            @Param("assistantId") Long assistantId,
            @Param("vectorIds") List<String> vectorIds
    );
}
