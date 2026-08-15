package com.interviewguide.interview.service;

import com.interviewguide.common.exception.BusinessException;
import com.interviewguide.interview.dto.InterviewTurnView;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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
                    "a CJK font is required to generate the interview PDF; configure fonts-noto-cjk or agent.pdf-font-path");
        }
        for (Path candidate : candidates) {
            try {
                return renderWithFont(candidate, sessionId, status, totalQuestions, turns, finalEvaluation);
            } catch (IOException | IllegalArgumentException error) {
                log.warn("Unable to render interview PDF using font {}", candidate, error);
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
                    throw new IOException("No usable font found in collection: " + resolvedFont);
                }
                font = PDType0Font.load(document, availableFonts.get(0), true);
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
        lines.add("Interview report");
        lines.add("Session ID: " + sessionId);
        lines.add("Status: " + status);
        lines.add("Configured questions: " + totalQuestions);
        lines.add("");
        int index = 1;
        for (InterviewTurnView turn : turns) {
            lines.add("Turn " + index++ + " - stage: " + turn.stage());
            lines.add("Question: " + turn.question());
            lines.add("Answer: " + (turn.answer() == null ? "" : turn.answer()));
            if (turn.evaluationSummary() != null && !turn.evaluationSummary().isBlank()) {
                lines.add("Evaluation: " + turn.evaluationSummary()
                        + (turn.score() == null ? "" : " (score: " + turn.score() + ")"));
            }
            lines.add("");
        }
        if (finalEvaluation != null && !finalEvaluation.isEmpty()) {
            lines.add("Final evaluation");
            lines.add("Overall score: " + finalEvaluation.getOrDefault("overallScore", "-"));
            lines.add("Summary: " + finalEvaluation.getOrDefault("summary", "-"));
            lines.add("Strengths: " + finalEvaluation.getOrDefault("strengths", "-"));
            lines.add("Weaknesses: " + finalEvaluation.getOrDefault("weaknesses", "-"));
            lines.add("Suggestions: " + finalEvaluation.getOrDefault("suggestions", "-"));
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
