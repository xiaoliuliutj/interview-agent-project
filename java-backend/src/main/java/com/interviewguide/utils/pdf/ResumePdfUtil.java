package com.interviewguide.utils.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Renders plain text into PDF pages for the resume export endpoint. */
public final class ResumePdfUtil {
    /** Prevents construction of this stateless renderer. */
    private ResumePdfUtil() {
    }

    /** Writes wrapped text into pages using the configured CJK-capable font. */
    public static void addTextPages(PDDocument document, PDType0Font font, String text) throws IOException {
        // Split source paragraphs first so each logical line remains readable.
        List<String> lines = new ArrayList<>();
        for (String source : text.split("\\R", -1)) {
            String line = source;
            // Wrap long source lines to fit the fixed PDF text area.
            while (line.length() > 55) {
                lines.add(line.substring(0, 55));
                line = line.substring(55);
            }
            lines.add(line);
        }
        PDPageContentStream stream = null;
        int lineCount = 0;
        for (String line : lines) {
            // Start a fresh page after the current page reaches its line limit.
            if (stream == null || lineCount >= 48) {
                if (stream != null) {
                    stream.endText();
                    stream.close();
                }
                PDPage page = new PDPage();
                document.addPage(page);
                stream = new PDPageContentStream(document, page);
                stream.beginText();
                stream.setFont(font, 10);
                stream.setLeading(14);
                stream.newLineAtOffset(40, 750);
                lineCount = 0;
            }
            // Replace literal tab escapes with spaces before PDFBox draws the text.
            stream.showText(line.replace("\\t", "  "));
            stream.newLine();
            lineCount++;
        }
        // Close the final text stream so the generated document remains valid.
        if (stream != null) {
            stream.endText();
            stream.close();
        }
    }
}
