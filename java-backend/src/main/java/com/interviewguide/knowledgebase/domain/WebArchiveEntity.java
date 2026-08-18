package com.interviewguide.knowledgebase.domain;

/** Represents the immutable Markdown archive returned for a crawl preview. */
public record WebArchiveEntity(String filename, String contentType, byte[] content) {
}
