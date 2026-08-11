package com.interview.agent.upper.service;

import com.interview.agent.upper.api.dto.InterviewTurnView;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Renders the candidate-visible interview transcript as a downloadable PDF. */
@Service
public class InterviewReportPdfService {
    private static final Logger log = LoggerFactory.getLogger(InterviewReportPdfService.class);
    private final Path fontPath;

    public InterviewReportPdfService(@Value("${agent.pdf-font-path}") String configuredFontPath) {
        this.fontPath = configuredFontPath == null || configuredFontPath.isBlank()
                ? null : Path.of(configuredFontPath);
    }

    public byte[] render(String sessionId, String status, int totalQuestions,
                         List<InterviewTurnView> turns, Map<String, Object> finalEvaluation) {
        List<Path> candidates = fontCandidates();
        if (candidates.isEmpty()) {
            throw new BusinessException("INTERVIEW_PDF_FONT_REQUIRED",
                    "未找到可用中文字体。请挂载字体文件，或使用包含 fonts-noto-cjk 的服务镜像。");
        }
        for (Path candidate : candidates) {
            try {
                return renderWithFont(candidate, sessionId, status, totalQuestions, turns, finalEvaluation);
            } catch (IOException error) {
                log.warn("无法使用 PDF 字体 {}，尝试下一个候选字体", candidate, error);
            }
        }
        throw new BusinessException("INTERVIEW_PDF_EXPORT_FAILED", "unable to generate interview PDF");
    }

    private List<Path> fontCandidates() {
        List<Path> candidates = new ArrayList<>();
        if (fontPath != null && Files.isRegularFile(fontPath)) candidates.add(fontPath);
        for (Path candidate : List.of(
                Path.of("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"),
                Path.of("/usr/share/fonts/opentype/noto/NotoSansCJKsc-Regular.otf"),
                Path.of("/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc"))) {
            if (Files.isRegularFile(candidate) && !candidates.contains(candidate)) candidates.add(candidate);
        }
        return candidates;
    }

    private byte[] renderWithFont(Path resolvedFont, String sessionId, String status, int totalQuestions,
                                  List<InterviewTurnView> turns, Map<String, Object> finalEvaluation) throws IOException {
        TrueTypeCollection collection = null;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDType0Font font;
            if (resolvedFont.getFileName().toString().toLowerCase().endsWith(".ttc")) {
                collection = new TrueTypeCollection(resolvedFont.toFile());
                List<TrueTypeFont> availableFonts = new ArrayList<>();
                collection.processAllFonts(availableFonts::add);
                if (availableFonts.isEmpty()) {
                    throw new IOException("字体集合中没有可用字体: " + resolvedFont);
                }
                TrueTypeFont firstFont = availableFonts.get(0);
                font = PDType0Font.load(document, firstFont, true);
            } else {
                font = PDType0Font.load(document, resolvedFont.toFile());
            }
            addPages(document, font, reportLines(sessionId, status, totalQuestions, turns, finalEvaluation));
            document.save(output);
            return output.toByteArray();
        } finally {
            if (collection != null) collection.close();
        }
    }

    private List<String> reportLines(String sessionId, String status, int totalQuestions,
                                     List<InterviewTurnView> turns, Map<String, Object> finalEvaluation) {
        List<String> lines = new ArrayList<>();
        lines.add("模拟面试记录");
        lines.add("会话 ID: " + sessionId);
        lines.add("状态: " + status);
        lines.add("动态题量安全上限: " + totalQuestions);
        lines.add("");
        int index = 1;
        for (InterviewTurnView turn : turns) {
            lines.add("第 " + index++ + " 题（" + turn.stage() + "）");
            lines.add("问题: " + turn.question());
            lines.add("回答: " + (turn.answer() == null ? "" : turn.answer()));
            if (turn.evaluationSummary() != null && !turn.evaluationSummary().isBlank()) {
                lines.add("回答评估: " + turn.evaluationSummary()
                        + (turn.score() == null ? "" : "（" + turn.score() + " 分）"));
            }
            lines.add("");
        }
        if (finalEvaluation != null && !finalEvaluation.isEmpty()) {
            lines.add("最终评估报告");
            lines.add("综合评分: " + finalEvaluation.getOrDefault("overallScore", "-"));
            lines.add("综合评价: " + finalEvaluation.getOrDefault("summary", "-"));
            lines.add("表现较好: " + finalEvaluation.getOrDefault("strengths", "-"));
            lines.add("待提升: " + finalEvaluation.getOrDefault("weaknesses", "-"));
            lines.add("建议: " + finalEvaluation.getOrDefault("suggestions", "-"));
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
