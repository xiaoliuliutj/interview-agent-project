package com.interview.agent.upper.service;

import com.interview.agent.upper.api.dto.CreateInterviewRequest;
import com.interview.agent.upper.api.dto.InterviewView;
import com.interview.agent.upper.api.dto.LegacyCreateInterviewRequest;
import com.interview.agent.upper.api.dto.LegacyCurrentQuestion;
import com.interview.agent.upper.api.dto.LegacyInterviewQuestion;
import com.interview.agent.upper.api.dto.LegacyInterviewSession;
import com.interview.agent.upper.api.dto.LegacyInterviewListItem;
import com.interview.agent.upper.api.dto.LegacySubmitAnswerResponse;
import com.interview.agent.upper.domain.CandidateEntity;
import com.interview.agent.upper.domain.JobDescriptionEntity;
import com.interview.agent.upper.domain.ResumeEntity;
import com.interview.agent.upper.repository.CandidateRepository;
import com.interview.agent.upper.repository.JobDescriptionRepository;
import com.interview.agent.upper.repository.ResumeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.stream.IntStream;

/** 将保留的 React 旧 DTO 适配到新的 Java 领域服务。 */
@Service
public class LegacyInterviewFacade {
    private final InterviewService interviewService;
    private final CandidateRepository candidateRepository;
    private final ResumeRepository resumeRepository;
    private final JobDescriptionRepository jobDescriptionRepository;
    private final String demoUserId;

    public LegacyInterviewFacade(
            InterviewService interviewService,
            CandidateRepository candidateRepository,
            ResumeRepository resumeRepository,
            JobDescriptionRepository jobDescriptionRepository,
            @Value("${agent.demo-user-id:demo-user}") String demoUserId) {
        this.interviewService = interviewService;
        this.candidateRepository = candidateRepository;
        this.resumeRepository = resumeRepository;
        this.jobDescriptionRepository = jobDescriptionRepository;
        this.demoUserId = demoUserId;
    }

    public LegacyInterviewSession create(LegacyCreateInterviewRequest request, String userId) {
        String effectiveUserId = userId == null || userId.isBlank() ? demoUserId : userId;
        String resumeId = request.resumeId() == null
                ? Long.toString(System.currentTimeMillis())
                : request.resumeId().toString();
        String candidateId = "candidate-" + resumeId;
        candidateRepository.findById(candidateId).orElseGet(() ->
                candidateRepository.save(new CandidateEntity(candidateId, effectiveUserId, "默认候选人")));
        resumeRepository.save(new ResumeEntity(
                resumeId, candidateId, 1, request.resumeText() == null ? "" : request.resumeText()));

        String jdId = null;
        if (request.jdText() != null && !request.jdText().isBlank()) {
            jdId = "jd-" + UUID.randomUUID();
            jobDescriptionRepository.save(new JobDescriptionEntity(
                    jdId, "目标岗位", 1, request.jdText()));
        }
        InterviewView view = interviewService.start(new CreateInterviewRequest(
                effectiveUserId, candidateId, resumeId, jdId,
                request.questionCount() == null ? 6 : request.questionCount()));
        return toLegacySession(view, request.resumeText());
    }

    public LegacySubmitAnswerResponse submit(String sessionId, String answer, String runId) {
        InterviewView view = interviewService.submitAnswer(
                sessionId, userForSession(sessionId), answer, runId);
        boolean completed = "COMPLETED".equals(view.status());
        int currentIndex = Math.min(interviewService.turns(sessionId).size(), view.totalQuestions());
        return new LegacySubmitAnswerResponse(
                !completed,
                completed ? null : question(view),
                currentIndex,
                view.totalQuestions());
    }

    public LegacyInterviewSession get(String sessionId) {
        InterviewView view = interviewService.view(sessionId);
        String resumeText = resumeRepository.findById(view.resumeId())
                .map(ResumeEntity::getContent)
                .orElse("");
        return toLegacySession(view, resumeText);
    }

    public LegacyCurrentQuestion currentQuestion(String sessionId) {
        InterviewView view = interviewService.view(sessionId);
        boolean completed = "COMPLETED".equals(view.status());
        return new LegacyCurrentQuestion(
                completed,
                completed ? null : question(view),
                completed ? "面试已结束" : null);
    }

    public List<LegacyInterviewListItem> list(String userId) {
        String effectiveUserId = effectiveUser(userId);
        return interviewService.list(effectiveUserId).stream().map(view ->
                new LegacyInterviewListItem(
                        view.sessionId(), null, "MEDIUM", parseLongOrNull(view.resumeId()),
                        view.totalQuestions(), view.status(), null, null, null,
                        view.createdAt(), "COMPLETED".equals(view.status()) ? view.updatedAt() : null))
                .toList();
    }

    public LegacyInterviewSession unfinished(String resumeId, String userId) {
        InterviewView view = interviewService.findUnfinished(effectiveUser(userId), resumeId);
        if (view == null) {
            return null;
        }
        String resumeText = resumeRepository.findById(view.resumeId())
                .map(ResumeEntity::getContent).orElse("");
        return toLegacySession(view, resumeText);
    }

    public void saveDraft(String sessionId, String answer, String userId) {
        interviewService.saveDraft(sessionId, effectiveUser(userId), answer);
    }

    public void complete(String sessionId, String userId) {
        interviewService.complete(sessionId, effectiveUser(userId));
    }

    public void delete(String sessionId, String userId) {
        interviewService.delete(sessionId, effectiveUser(userId));
    }

    public List<Map<String, Object>> turns(String sessionId) {
        List<com.interview.agent.upper.domain.InterviewTurnEntity> turns = interviewService.turns(sessionId);
        return IntStream.range(0, turns.size()).mapToObj(index -> {
            com.interview.agent.upper.domain.InterviewTurnEntity turn = turns.get(index);
            return Map.<String, Object>of(
                    "questionIndex", index,
                    "question", turn.getQuestion() == null ? "" : turn.getQuestion(),
                    "category", "INTERVIEW",
                    "userAnswer", turn.getCandidateAnswer() == null ? "" : turn.getCandidateAnswer(),
                    "score", 0,
                    "feedback", turn.getEvaluationSummary() == null ? "" : turn.getEvaluationSummary(),
                    "answeredAt", turn.getCreatedAt());
        }).toList();
    }

    private String userForSession(String sessionId) {
        return interviewService.view(sessionId).userId();
    }

    private LegacyInterviewSession toLegacySession(InterviewView view, String resumeText) {
        boolean completed = "COMPLETED".equals(view.status());
        int currentIndex = Math.min(interviewService.turns(view.sessionId()).size(), view.totalQuestions());
        return new LegacyInterviewSession(
                view.sessionId(), resumeText, view.totalQuestions(), currentIndex,
                completed ? List.of() : List.of(question(view)),
                completed ? "COMPLETED" : "IN_PROGRESS");
    }

    private LegacyInterviewQuestion question(InterviewView view) {
        return new LegacyInterviewQuestion(
                Math.min(interviewService.turns(view.sessionId()).size(), view.totalQuestions()),
                view.currentQuestion(), "AGENT", "INTERVIEW",
                null, null, null);
    }

    private String effectiveUser(String userId) {
        return userId == null || userId.isBlank() ? demoUserId : userId;
    }

    private Long parseLongOrNull(String value) {
        try {
            return value == null ? null : Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
