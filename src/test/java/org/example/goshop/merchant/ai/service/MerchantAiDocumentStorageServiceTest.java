package org.example.goshop.merchant.ai.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ObjectMetadata;
import org.example.goshop.common.exception.BusinessException;
import org.example.goshop.merchant.ai.config.MerchantAiDocumentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/** 导购文档格式校验与私有 OSS 上传测试。 */
@ExtendWith(MockitoExtension.class)
class MerchantAiDocumentStorageServiceTest {

    @Mock
    private OSS ossClient;

    private MerchantAiDocumentStorageService storageService;

    @BeforeEach
    void setUp() {
        storageService = new MerchantAiDocumentStorageService(
                ossClient,
                new MerchantAiDocumentProperties(
                        5,
                        20,
                        "merchant-ai-private"
                )
        );
    }

    @Test
    void shouldPrepareUtf8MarkdownAndRemovePath() {
        byte[] content = "# 商品指南\n适合家庭用户。"
                .getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "../guides/product.md",
                "text/markdown; charset=UTF-8",
                content
        );

        MerchantAiDocumentStorageService.PreparedDocument prepared =
                storageService.prepare(file);

        assertEquals("product.md", prepared.originalFilename());
        assertEquals("MARKDOWN", prepared.fileType());
        assertEquals("text/markdown", prepared.mimeType());
        assertEquals((long) content.length, prepared.sizeBytes());
        assertEquals(64, prepared.sha256().length());
    }

    @Test
    void shouldRecognizeDocxContainerMarkers() {
        byte[] docx = (
                "PK\u0003\u0004...[Content_Types].xml..."
                        + "word/document.xml"
        ).getBytes(StandardCharsets.ISO_8859_1);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "guide.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docx
        );

        MerchantAiDocumentStorageService.PreparedDocument prepared =
                storageService.prepare(file);

        assertEquals("DOCX", prepared.fileType());
    }

    @Test
    void shouldRejectSpoofedExtensionAndMismatchedMime() {
        MockMultipartFile fakePdf = new MockMultipartFile(
                "file",
                "guide.pdf",
                "application/pdf",
                "not a pdf".getBytes(StandardCharsets.UTF_8)
        );
        MockMultipartFile wrongMime = new MockMultipartFile(
                "file",
                "guide.txt",
                "application/pdf",
                "plain text".getBytes(StandardCharsets.UTF_8)
        );

        assertThrows(
                BusinessException.class,
                () -> storageService.prepare(fakePdf)
        );
        assertThrows(
                BusinessException.class,
                () -> storageService.prepare(wrongMime)
        );
    }

    @Test
    void shouldUploadToMerchantPrivatePrefix() {
        byte[] pdf = "%PDF-1.7\nmock"
                .getBytes(StandardCharsets.US_ASCII);
        MerchantAiDocumentStorageService.PreparedDocument prepared =
                storageService.prepare(
                        new MockMultipartFile(
                                "file",
                                "guide.pdf",
                                "application/pdf",
                                pdf
                        )
                );

        MerchantAiDocumentStorageService.UploadResult result =
                storageService.upload(
                        7001L,
                        9001L,
                        prepared
                );

        ArgumentCaptor<String> keyCaptor =
                ArgumentCaptor.forClass(String.class);
        verify(ossClient).putObject(
                eq("merchant-ai-private"),
                keyCaptor.capture(),
                any(InputStream.class),
                any(ObjectMetadata.class)
        );
        assertTrue(
                keyCaptor.getValue().startsWith(
                        "merchant-ai/documents/7001/"
                )
        );
        assertEquals(keyCaptor.getValue(), result.objectKey());
    }
}
