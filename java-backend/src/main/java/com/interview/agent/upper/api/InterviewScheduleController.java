package com.interview.agent.upper.api;

import com.interview.agent.upper.api.dto.ApiResult;
import com.interview.agent.upper.api.dto.ScheduleParseRequest;
import com.interview.agent.upper.api.dto.ScheduleRequest;
import com.interview.agent.upper.api.dto.ScheduleView;
import com.interview.agent.upper.domain.ScheduleStatus;
import com.interview.agent.upper.service.BusinessException;
import com.interview.agent.upper.service.InterviewScheduleService;
import com.interview.agent.upper.service.UserIdentityResolver;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/interview-schedule")
public class InterviewScheduleController {
    private final InterviewScheduleService service;
    private final UserIdentityResolver identity;

    public InterviewScheduleController(InterviewScheduleService service, UserIdentityResolver identity) {
        this.service = service;
        this.identity = identity;
    }

    @PostMapping("/parse")
    public ApiResult<ScheduleView> parse(@RequestBody ScheduleParseRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(service.parse(request, userId));
    }

    @PostMapping
    public ApiResult<ScheduleView> create(@Valid @RequestBody ScheduleRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(service.create(request, userId));
    }

    @GetMapping("/{id}")
    public ApiResult<ScheduleView> get(@PathVariable Long id,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(service.get(id, userId));
    }

    @GetMapping
    public ApiResult<List<ScheduleView>> list(
            @RequestParam(required = false) String ignoredUserId,
            @RequestParam(required = false) ScheduleStatus status,
            @RequestParam(required = false) Instant start,
            @RequestParam(required = false) Instant end,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(service.list(identity.require(userId), status, start, end));
    }

    @PutMapping("/{id}")
    public ApiResult<ScheduleView> update(@PathVariable Long id,
            @Valid @RequestBody ScheduleRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        if (!identity.require(userId).equals(request.userId())) {
            throw new BusinessException("USER_ID_MISMATCH", "schedule userId does not match X-User-Id");
        }
        return ApiResult.success(service.update(id, request, userId));
    }

    @PatchMapping("/{id}/status")
    public ApiResult<ScheduleView> updateStatus(@PathVariable Long id,
            @RequestParam ScheduleStatus status,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ApiResult.success(service.updateStatus(id, status, userId));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable Long id,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        service.delete(id, userId);
        return ApiResult.success(null);
    }
}
