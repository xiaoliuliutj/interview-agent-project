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
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.stream.IntStream;

/** 将保留的 React 旧 DTO 适配到新的 Java 领域服务。 */
@Service
public class LegacyInterviewFacade {
    private final InterviewService interviewService;
    private final CandidateRepository candidateRepository;
    private final ResumeRepository resumeRepository;
    private final JobDescriptionRepository jobDescriptionRepository;
    private final UserIdentityResolver userIdentityResolver;
    private final BusinessIdGenerator idGenerator;

    public LegacyInterviewFacade(
            InterviewService interviewService,
            CandidateRepository candidateRepository,
            ResumeRepository resumeRepository,
            JobDescriptionRepository jobDescriptionRepository,
            UserIdentityResolver userIdentityResolver,
            BusinessIdGenerator idGenerator) {
        this.interviewService = interviewService;
        this.candidateRepository = candidateRepository;
        this.resumeRepository = resumeRepository;
        this.jobDescriptionRepository = jobDescriptionRepository;
        this.userIdentityResolver = userIdentityResolver;
        this.idGenerator = idGenerator;
    }

    public LegacyInterviewSession create(LegacyCreateInterviewRequest request, String userId) {
        String effectiveUserId = userIdentityResolver.require(userId);
        String resumeId = request.resumeId() == null
                ? idGenerator.next()
                : request.resumeId().toString();
        String candidateId = "candidate-" + resumeId;
        candidateRepository.findById(candidateId).orElseGet(() ->
                candidateRepository.save(new CandidateEntity(candidateId, effectiveUserId, candidateId)));
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
                request.questionCount() == null ? 6 : request.questionCount(),
                InterviewService.normalizeDifficulty(request.difficulty()), request.skillId(),
                request.customCategories() == null ? List.of() : request.customCategories()));
        return toLegacySession(view, request.resumeText());
    }

    public LegacySubmitAnswerResponse submit(String sessionId, String userId, String answer, String runId) {
        String owner = userIdentityResolver.require(userId);
        if (!owner.equals(interviewService.view(sessionId).userId())) {
            throw new BusinessException("SESSION_ACCESS_DENIED", "session does not belong to current user");
        }
        InterviewView view = interviewService.submitAnswer(
                sessionId, owner, answer, runId);
        boolean completed = "COMPLETED".equals(view.status());
        int currentIndex = Math.min(interviewService.turns(sessionId).size(), view.totalQuestions());
        return new LegacySubmitAnswerResponse(
                !completed,
                completed ? null : question(view),
                currentIndex,
                view.totalQuestions());
    }

    public LegacyInterviewSession get(String sessionId, String userId) {
        InterviewView view = interviewService.view(sessionId);
        if (!userIdentityResolver.require(userId).equals(view.userId())) {
            throw new BusinessException("SESSION_ACCESS_DENIED", "session does not belong to current user");
        }
        String resumeText = resumeRepository.findById(view.resumeId())
                .map(ResumeEntity::getContent)
                .orElse("");
        return toLegacySession(view, resumeText);
    }

    public LegacyCurrentQuestion currentQuestion(String sessionId, String userId) {
        InterviewView view = interviewService.view(sessionId);
        if (!userIdentityResolver.require(userId).equals(view.userId())) {
            throw new BusinessException("SESSION_ACCESS_DENIED", "session does not belong to current user");
        }
        boolean completed = "COMPLETED".equals(view.status());
        return new LegacyCurrentQuestion(
                completed,
                completed ? null : question(view),
                completed ? "面试已结束" : null);
    }

    public List<LegacyInterviewListItem> list(String userId) {
        String effectiveUserId = userIdentityResolver.require(userId);
        return interviewService.list(effectiveUserId).stream().map(view ->
                new LegacyInterviewListItem(
                        view.sessionId(), view.skillId(), view.difficulty(), parseLongOrNull(view.resumeId()),
                        view.totalQuestions(), view.status(), view.evaluateStatus(), view.evaluateError(), view.overallScore(),
                        view.createdAt(), "COMPLETED".equals(view.status()) ? view.updatedAt() : null))
                .toList();
    }

    public LegacyInterviewSession unfinished(String resumeId, String userId) {
        InterviewView view = interviewService.findUnfinished(userIdentityResolver.require(userId), resumeId);
        if (view == null) {
            return null;
        }
        String resumeText = resumeRepository.findById(view.resumeId())
                .map(ResumeEntity::getContent).orElse("");
        return toLegacySession(view, resumeText);
    }

    public void saveDraft(String sessionId, String answer, String userId) {
        interviewService.saveDraft(sessionId, userIdentityResolver.require(userId), answer);
    }

    public void complete(String sessionId, String userId) {
        interviewService.complete(sessionId, userIdentityResolver.require(userId));
    }

    public void delete(String sessionId, String userId) {
        interviewService.delete(sessionId, userIdentityResolver.require(userId));
    }

    public List<Map<String, Object>> turns(String sessionId) {
        List<com.interview.agent.upper.domain.InterviewTurnEntity> turns = interviewService.turns(sessionId);
        return IntStream.range(0, turns.size()).mapToObj(index -> {
            com.interview.agent.upper.domain.InterviewTurnEntity turn = turns.get(index);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("questionIndex", index);
            result.put("question", turn.getQuestion() == null ? "" : turn.getQuestion());
            result.put("category", turn.getStage());
            result.put("userAnswer", turn.getCandidateAnswer() == null ? "" : turn.getCandidateAnswer());
            result.put("score", turn.getScore() == null ? 0 : turn.getScore());
            result.put("answerSummary", turn.getAnswerSummary() == null ? "" : turn.getAnswerSummary());
            result.put("strengths", parseJsonArray(turn.getStrengthsJson()));
            result.put("weaknesses", parseJsonArray(turn.getWeaknessesJson()));
            result.put("preferences", parseJsonArray(turn.getPreferencesJson()));
            result.put("feedback", turn.getEvaluationSummary() == null ? "" : turn.getEvaluationSummary());
            result.put("answeredAt", turn.getCreatedAt() == null ? java.time.Instant.EPOCH : turn.getCreatedAt());
            return result;
        }).toList();
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

    public Map<String, Object> report(String sessionId, String userId) {
        InterviewView view = interviewService.view(sessionId);
        if (!userIdentityResolver.require(userId).equals(view.userId())) {
            throw new BusinessException("SESSION_ACCESS_DENIED", "session does not belong to current user");
        }
        List<Map<String, Object>> turnViews = turns(sessionId);
        return Map.of(
                "sessionId", view.sessionId(),
                "totalQuestions", view.totalQuestions(),
                "overallScore", view.overallScore() == null ? 0 : view.overallScore(),
                "overallFeedback", view.finalSummary() == null ? "" : view.finalSummary(),
                "questionDetails", turnViews,
                "categoryScores", categoryScores(turnViews),
                "strengths", flatten(turnViews, "strengths"),
                "improvements", flatten(turnViews, "weaknesses"),
                "referenceAnswers", List.of());
    }

    private List<?> parseJsonArray(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try { return new com.fasterxml.jackson.databind.ObjectMapper().readValue(raw, List.class); }
        catch (Exception ignored) { return List.of(); }
    }

    private List<Object> flatten(List<Map<String, Object>> values, String key) {
        return values.stream()
                .flatMap(item -> ((List<?>) item.getOrDefault(key, List.of())).stream())
                .map(value -> (Object) value)
                .distinct().toList();
    }

    private List<Map<String, Object>> categoryScores(List<Map<String, Object>> values) {
        return values.stream().filter(item -> item.get("category") != null).collect(java.util.stream.Collectors.groupingBy(
                item -> String.valueOf(item.get("category")),
                java.util.LinkedHashMap::new,
                java.util.stream.Collectors.toList())).entrySet().stream().map(entry -> {
            List<Map<String, Object>> items = entry.getValue();
            double average = items.stream().map(item -> item.get("score"))
                    .filter(Number.class::isInstance).mapToInt(item -> ((Number) item).intValue()).average().orElse(0);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("category", entry.getKey());
            result.put("score", Math.round(average));
            result.put("count", items.size());
            return result;
        }).toList();
    }

    private Long parseLongOrNull(String value) {
        try {
            return value == null ? null : Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
