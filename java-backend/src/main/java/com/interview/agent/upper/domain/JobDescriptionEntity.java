package com.interview.agent.upper.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "job_descriptions")
public class JobDescriptionEntity {
    @Id
    private String id;
    private String title;
    private int version;
    @Column(columnDefinition = "TEXT")
    private String content;

    protected JobDescriptionEntity() {
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
