package com.interviewguide.resume.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumeAnalysisEntityTest {

    @Test
    void retryableFailureKeepsTaskRunnableAndTracksAttempts() {
        ResumeAnalysisEntity analysis = new ResumeAnalysisEntity("resume-1", "Java 后端实习生");

        assertTrue(analysis.canBeginAttempt());
        analysis.beginAttempt();
        analysis.recordRetryableFailure("lower service timeout");

        assertEquals("PROCESSING", analysis.getStatus());
        assertEquals(1, analysis.getRetryCount());
        assertNotNull(analysis.getLastAttemptAt());
        assertEquals("lower service timeout", analysis.getError());
        assertTrue(analysis.canBeginAttempt());
    }

    @Test
    void cancelledTaskCannotStartAnotherAttempt() {
        ResumeAnalysisEntity analysis = new ResumeAnalysisEntity("resume-1", "Java 后端实习生");
        analysis.beginAttempt();
        analysis.cancel();

        assertEquals("CANCELLED", analysis.getStatus());
        assertFalse(analysis.canBeginAttempt());
    }
}
