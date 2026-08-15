package com.interviewguide.interview.domain;

public class JobDescriptionEntity {
    private String id;
    private String title;
    private int version;
    private String content;

    public JobDescriptionEntity() {
    }

    public JobDescriptionEntity(String id, String title, int version, String content) {
        this.id = id;
        this.title = title;
        this.version = version;
        this.content = content;
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public int getVersion() { return version; }
    public String getContent() { return content; }
}
