package com.interview.agent.upper.api;

import com.interview.agent.upper.api.dto.ApiResult;
import com.interview.agent.upper.api.dto.ScheduleParseRequest;
import com.interview.agent.upper.api.dto.ScheduleRequest;
import com.interview.agent.upper.api.dto.ScheduleView;
import com.interview.agent.upper.domain.ScheduleStatus;
import com.interview.agent.upper.service.InterviewScheduleService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/interview-schedule")
public class InterviewScheduleController {
    private final InterviewScheduleService service;

    public InterviewScheduleController(InterviewScheduleService service) {
        this.service = service;
    }

    @PostMapping("/parse")
    public ApiResult<ScheduleView> parse(@RequestBody ScheduleParseRequest request) {
        return ApiResult.success(service.parse(request));
    }

    @PostMapping
    public ApiResult<ScheduleView> create(@Valid @RequestBody ScheduleRequest request) {
        return ApiResult.success(service.create(request));
    }

    @GetMapping("/{id}")
    public ApiResult<ScheduleView> get(@PathVariable Long id) {
        return ApiResult.success(service.get(id));
    }

    @GetMapping
    public ApiResult<List<ScheduleView>> list(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) ScheduleStatus status,
            @RequestParam(required = false) Instant start,
            @RequestParam(required = false) Instant end) {
        return ApiResult.success(service.list(userId, status, start, end));
    }

    @PutMapping("/{id}")
    public ApiResult<ScheduleView> update(
            @PathVariable Long id, @Valid @RequestBody ScheduleRequest request) {
        return ApiResult.success(service.update(id, request));
    }

    @PatchMapping("/{id}/status")
    public ApiResult<ScheduleView> updateStatus(
            @PathVariable Long id, @RequestParam ScheduleStatus status) {
        return ApiResult.success(service.updateStatus(id, status));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResult.success(null);
    }
}
