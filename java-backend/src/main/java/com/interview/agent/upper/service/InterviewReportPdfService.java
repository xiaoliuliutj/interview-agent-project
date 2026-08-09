package com.interview.agent.upper.service;

import com.interview.agent.upper.api.dto.InterviewTurnView;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Renders the candidate-visible interview transcript as a downloadable PDF. */
@Service
public class InterviewReportPdfService {
    private final Path fontPath;

    public InterviewReportPdfService(@Value("${agent.pdf-font-path}") String configuredFontPath) {
        this.fontPath = configuredFontPath == null || configuredFontPath.isBlank()
                ? null : Path.of(configuredFontPath);
    }

    public byte[] render(String sessionId, String status, int totalQuestions,
                         List<InterviewTurnView> turns) {
        if (fontPath == null || !Files.isRegularFile(fontPath)) {
            throw new BusinessException("INTERVIEW_PDF_FONT_REQUIRED",
                    "AGENT_PDF_FONT_PATH must point to a readable CJK font");
        }
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDType0Font font = PDType0Font.load(document, fontPath.toFile());
            addPages(document, font, reportLines(sessionId, status, totalQuestions, turns));
            document.save(output);
            return output.toByteArray();
        } catch (IOException error) {
            throw new BusinessException("INTERVIEW_PDF_EXPORT_FAILED", "unable to generate interview PDF");
        }
    }

    private List<String> reportLines(String sessionId, String status, int totalQuestions,
                                     List<InterviewTurnView> turns) {
        List<String> lines = new ArrayList<>();
        lines.add("模拟面试记录");
        lines.add("会话 ID: " + sessionId);
        lines.add("状态: " + status);
        lines.add("规划题数: " + totalQuestions);
        lines.add("");
        int index = 1;
        for (InterviewTurnView turn : turns) {
            lines.add("第 " + index++ + " 题（" + turn.stage() + "）");
            lines.add("问题: " + turn.question());
            lines.add("回答: " + (turn.answer() == null ? "" : turn.answer()));
            lines.add("");
        }
        return lines;
    }

    private void addPages(PDDocument document, PDType0Font font, List<String> sourceLines) throws IOException {
        PDPageContentStream stream = null;
        int lineCount = 0;
        try {
            for (String source : sourceLines) {
                for (String line : wrap(source)) {
                    if (stream == null || lineCount >= 48) {
                        if (stream != null) {
                            stream.endText();
                            stream.close();
                        }
                        document.addPage(new PDPage());
                        stream = new PDPageContentStream(document, document.getPage(document.getNumberOfPages() - 1));
                        stream.beginText();
                        stream.setFont(font, 10);
                        stream.setLeading(14);
                        stream.newLineAtOffset(40, 750);
                        lineCount = 0;
                    }
                    stream.showText(line.replace("\t", "  "));
                    stream.newLine();
                    lineCount++;
                }
            }
        } finally {
            if (stream != null) {
                stream.endText();
                stream.close();
            }
        }
    }

    private List<String> wrap(String source) {
        List<String> lines = new ArrayList<>();
        String normalized = source == null ? "" : source.replace('\r', ' ').replace('\n', ' ');
        while (normalized.length() > 55) {
            lines.add(normalized.substring(0, 55));
            normalized = normalized.substring(55);
        }
        lines.add(normalized);
        return lines;
    }
}
