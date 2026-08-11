package com.interview.agent.upper.service;

import com.interview.agent.upper.api.dto.InterviewTurnView;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterviewReportPdfServiceTest {

    @Test
    void rendersReadableChineseTranscriptAndEvaluation() throws Exception {
        Path font = requireFont(
                "C:/Windows/Fonts/simhei.ttf",
                "C:/Windows/Fonts/NotoSansSC-VF.ttf",
                "/usr/share/fonts/opentype/noto/NotoSansCJKsc-Regular.otf");

        InterviewReportPdfService service = new InterviewReportPdfService(font.toString());
        byte[] content = service.render(
                "session-1", "COMPLETED", 20,
                List.of(new InterviewTurnView(
                        0, "PROJECT", "请介绍项目架构", "我采用分层架构",
                        "回答覆盖了核心职责", 86, Instant.now())),
                Map.of(
                        "overallScore", 86,
                        "summary", "整体表现良好",
                        "strengths", List.of("表达清晰"),
                        "weaknesses", List.of("边界说明不足"),
                        "suggestions", List.of("补充异常场景")));

        assertTrue(content.length > 1_000);
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(content))) {
            assertEquals(1, document.getNumberOfPages());
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("模拟面试记录"));
            assertTrue(text.contains("请介绍项目架构"));
            assertTrue(text.contains("回答评估"));
            assertTrue(text.contains("最终评估报告"));
            assertTrue(text.contains("整体表现良好"));
        }
    }

    @Test
    void rendersWithAChineseTrueTypeCollection() throws Exception {
        Path fontCollection = requireFont(
                "C:/Windows/Fonts/msyh.ttc",
                "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
                "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc");

        InterviewReportPdfService service = new InterviewReportPdfService(fontCollection.toString());
        byte[] content = service.render(
                "session-ttc", "COMPLETED", 20,
                List.of(new InterviewTurnView(
                        0, "FUNDAMENTAL", "请说明事务隔离级别", "我会结合脏读和幻读说明",
                        "回答基本完整", 82, Instant.now())),
                Map.of("overallScore", 82, "summary", "具备基础能力"));

        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(content))) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("请说明事务隔离级别"));
            assertTrue(text.contains("具备基础能力"));
        }
    }

    private static Path requireFont(String... candidates) {
        for (String candidate : candidates) {
            Path font = Path.of(candidate);
            if (Files.isRegularFile(font)) return font;
        }
        Assumptions.assumeTrue(false, "测试环境没有可用中文字体");
        throw new IllegalStateException("unreachable");
    }
}
