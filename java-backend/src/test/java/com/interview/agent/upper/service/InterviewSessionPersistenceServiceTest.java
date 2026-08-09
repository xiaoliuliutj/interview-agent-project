package com.interview.agent.upper.service;

import com.interview.agent.upper.domain.InterviewSessionEntity;
import com.interview.agent.upper.domain.InterviewTurnEntity;
import com.interview.agent.upper.repository.InterviewSessionRepository;
import com.interview.agent.upper.repository.InterviewTurnRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterviewSessionPersistenceServiceTest {

    @Test
    void replayedRunIsAcceptedBeforeCheckingTheNowStaleJpaVersion() {
        InterviewSessionRepository sessions = mock(InterviewSessionRepository.class);
        InterviewTurnRepository turns = mock(InterviewTurnRepository.class);
        InterviewSessionPersistenceService service = new InterviewSessionPersistenceService(sessions, turns);
        InterviewSessionEntity session = new InterviewSessionEntity(
                "session-1", "user-1", "candidate-1", "resume-1", null, 6);
        InterviewTurnEntity savedTurn = new InterviewTurnEntity(
                "session-1", "run-1", "current question", "candidate answer");

        when(sessions.findByIdForUpdate("session-1")).thenReturn(Optional.of(session));
        when(turns.findByRunId("run-1")).thenReturn(Optional.of(savedTurn));

        // The caller still holds the pre-write JPA version.  A replay must return
        // successfully because this run has already been persisted.
        service.applyAnswer("session-1", -1L, "run-1", "candidate answer", null);

        verify(turns, never()).save(savedTurn);
        verify(sessions, never()).save(session);
        verify(turns).findByRunId(anyString());
    }
}
