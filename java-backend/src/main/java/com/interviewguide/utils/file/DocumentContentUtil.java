package com.interviewguide.utils.file;

import com.interviewguide.common.exception.BusinessException;
import org.apache.tika.Tika;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Provides generic text extraction for uploaded text and binary documents. */
public final class DocumentContentUtil {
    /** Reuses Apache Tika for binary document formats. */
    private static final Tika TIKA = new Tika();

    /** Prevents construction because this class only exposes stateless technical operations. */
    private DocumentContentUtil() {
    }

    /** Extracts text without applying any module-specific validation or persistence rule. */
    public static String extractText(MultipartFile file, String filename) throws IOException {
        // Decode known text formats directly to preserve their source content.
        if (isTextDocument(filename, file.getContentType())) {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        }
        try {
            // Delegate PDF and Office-format extraction to the generic parser.
            return TIKA.parseToString(file.getInputStream());
        } catch (IOException error) {
            // Preserve transport I/O failures for the caller's declared contract.
            throw error;
        } catch (Exception error) {
            // Hide parser implementation details while retaining a stable application error.
            throw new BusinessException("DOCUMENT_PARSE_FAILED", "uploaded document parsing failed");
        }
    }

    /** Determines whether UTF-8 decoding can replace binary document parsing. */
    private static boolean isTextDocument(String filename, String contentType) {
        // Normalise the filename because supported extensions are case-insensitive.
        String lowered = filename.toLowerCase(Locale.ROOT);
        // Support the plain-text source formats used by both knowledge-base and resume imports.
        if (lowered.endsWith(".txt") || lowered.endsWith(".md") || lowered.endsWith(".markdown")) {
            return true;
        }
        // Trust a producer-declared text media type for other text-based formats.
        return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("text/");
    }

    /** Returns original bytes when available and otherwise encodes a legacy text record. */
    public static byte[] downloadBytes(byte[] originalBytes, String content) {
        // Exact source bytes preserve binary documents during download.
        return originalBytes == null ? (content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8))
                : originalBytes;
    }

    /** Supplies a valid media type when legacy rows have no stored type. */
    public static String downloadContentType(String contentType) {
        // A text fallback keeps the response header valid for legacy text records.
        return contentType == null || contentType.isBlank() ? "text/plain" : contentType;
    }

    /** Supplies a deterministic filename when a legacy record has no original filename. */
    public static String downloadFilename(String originalFilename, long id) {
        // Preserve a supplied source filename whenever it exists.
        return originalFilename == null || originalFilename.isBlank()
                ? "document-" + id + ".txt" : originalFilename;
    }
}
