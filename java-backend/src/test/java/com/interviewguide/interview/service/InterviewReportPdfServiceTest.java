package com.interviewguide.interview.service;

import com.interviewguide.interview.dto.InterviewTurnView;
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
    void rendersReadableTranscriptAndEvaluation() throws Exception {
        Path font = requireFont(
                "C:/Windows/Fonts/simhei.ttf",
                "C:/Windows/Fonts/NotoSansSC-VF.ttf",
                "/usr/share/fonts/opentype/noto/NotoSansCJKsc-Regular.otf");
        InterviewReportPdfService service = new InterviewReportPdfService(font.toString());
        byte[] content = service.render(
                "session-1", "COMPLETED", 20,
                List.of(new InterviewTurnView(0, "PROJECT", "Describe a Java project.", "I built one.",
                        "Clear explanation with relevant detail.", 86, Instant.now())),
                Map.of("overallScore", 86, "summary", "Solid performance.",
                        "strengths", List.of("clear communication"),
                        "weaknesses", List.of("limited detail"),
                        "suggestions", List.of("add measurable outcomes")));

        assertTrue(content.length > 1_000);
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(content))) {
            assertEquals(1, document.getNumberOfPages());
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("Interview report"));
            assertTrue(text.contains("Describe a Java project."));
            assertTrue(text.contains("I built one."));
            assertTrue(text.contains("Final evaluation"));
            assertTrue(text.contains("Solid performance."));
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
                List.of(new InterviewTurnView(0, "FUNDAMENTAL", "Explain a transaction.", "It is atomic.",
                        "Correct answer.", 82, Instant.now())),
                Map.of("overallScore", 82, "summary", "Good fundamentals."));

        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(content))) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("Explain a transaction."));
            assertTrue(text.contains("Good fundamentals."));
        }
    }

    private static Path requireFont(String... candidates) {
        for (String candidate : candidates) {
            Path font = Path.of(candidate);
            if (Files.isRegularFile(font)) return font;
        }
        Assumptions.assumeTrue(false, "No compatible CJK font is available for this test.");
        throw new IllegalStateException("unreachable");
    }
}
