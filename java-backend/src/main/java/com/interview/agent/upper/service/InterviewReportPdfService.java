package com.interview.agent.upper.service;

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
import java.util.Map;

/** 将已持久化的面试报告渲染为可下载 PDF；中文字体由部署环境提供。 */
@Service
public class InterviewReportPdfService {
    private final Path fontPath;

    public InterviewReportPdfService(@Value("${agent.pdf-font-path}") String configuredFontPath) {
        this.fontPath = configuredFontPath == null || configuredFontPath.isBlank()
                ? null : Path.of(configuredFontPath);
    }

    public byte[] render(String sessionId, String status, int totalQuestions, Map<String, Object> report) {
        if (fontPath == null || !Files.isRegularFile(fontPath)) {
            throw new BusinessException("INTERVIEW_PDF_FONT_REQUIRED",
                    "AGENT_PDF_FONT_PATH must point to a readable CJK font");
        }
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDType0Font font = PDType0Font.load(document, fontPath.toFile());
            addPages(document, font, reportLines(sessionId, status, totalQuestions, report));
            document.save(output);
            return output.toByteArray();
        } catch (IOException error) {
            throw new BusinessException("INTERVIEW_PDF_EXPORT_FAILED", "无法生成面试报告 PDF");
        }
    }

    private List<String> reportLines(String sessionId, String status, int totalQuestions, Map<String, Object> report) {
        List<String> lines = new ArrayList<>();
        lines.add("模拟面试报告");
        lines.add("会话 ID: " + sessionId);
        lines.add("状态: " + status);
        lines.add("题目数量: " + totalQuestions);
        lines.add("综合得分: " + report.getOrDefault("overallScore", "-"));
        lines.add("综合反馈: " + report.getOrDefault("overallFeedback", ""));
        lines.add("");
        Object details = report.get("questionDetails");
        if (details instanceof List<?> turns) {
            int index = 1;
            for (Object turnObject : turns) {
                if (!(turnObject instanceof Map<?, ?> rawTurn)) continue;
                lines.add("第 " + index++ + " 题");
                lines.add("问题: " + value(rawTurn, "question"));
                lines.add("回答: " + value(rawTurn, "userAnswer"));
                lines.add("得分: " + value(rawTurn, "score"));
                lines.add("评价: " + value(rawTurn, "feedback"));
                lines.add("");
            }
        }
        return lines;
    }

    private String value(Map<?, ?> values, String key) {
        Object value = values.get(key);
        return value == null ? "" : String.valueOf(value);
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
