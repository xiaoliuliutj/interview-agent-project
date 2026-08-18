package com.interviewguide.utils.web;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/** Normalises untyped values returned by the Python web crawler. */
public final class WebValueUtil {
    /** Prevents construction of this stateless utility. */
    private WebValueUtil() {
    }

    /** Converts a nullable crawler value to a non-null text value. */
    public static String stringValue(Object value) {
        // Empty text is easier for validation rules to reason about than null.
        return value == null ? "" : String.valueOf(value);
    }

    /** Preserves null when the crawler field is optional. */
    public static String nullableString(Object value) {
        // Parent URLs and stop reasons are absent for valid root pages.
        return value == null ? null : String.valueOf(value);
    }

    /** Converts a numeric crawler field without aborting a preview for malformed advisory metadata. */
    public static int intValue(Object value) {
        // A missing or malformed depth is treated as the root depth.
        return value instanceof Number number ? number.intValue() : 0;
    }

    /** Parses an optional ISO-8601 timestamp from the Python payload. */
    public static Instant parseInstant(String value) {
        // A malformed optional timestamp does not invalidate otherwise usable Markdown.
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /** Sanitises a crawler-provided filename before it is persisted or returned in a header. */
    public static String safeFilename(String filename, int index) {
        // Replace path separators, control characters, and Windows-reserved filename symbols.
        String value = filename == null ? ""
                : filename.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").strip();
        // Use a stable indexed filename when Python omitted one.
        if (value.isBlank()) {
            return String.format("%03d-web-page.md", index);
        }
        // Bound the filename so downstream storage and HTTP headers remain predictable.
        return value.substring(0, Math.min(value.length(), 180));
    }
}
