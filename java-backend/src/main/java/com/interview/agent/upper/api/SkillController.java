package com.interview.agent.upper.api;

import com.interview.agent.upper.agent.AgentGateway;
import com.interview.agent.upper.agent.dto.AgentResponse;
import com.interview.agent.upper.agent.dto.AgentSkillRequest;
import com.interview.agent.upper.api.dto.ApiResult;
import com.interview.agent.upper.service.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 旧 React 技能接口适配器；Skill 内容仍由 Python 下层的外置配置负责。 */
@RestController
@RequestMapping("/api/interview/skills")
public class SkillController {
    private final AgentGateway agentGateway;
    private final String demoUserId;

    public SkillController(
            AgentGateway agentGateway,
            @Value("${agent.demo-user-id:demo-user}") String demoUserId) {
        this.agentGateway = agentGateway;
        this.demoUserId = demoUserId;
    }

    @GetMapping
    public ApiResult<Object> list() {
        AgentResponse response = agentGateway.skills(request("agent.skills.list", "catalog"));
        return ApiResult.success(requiredOutput(response).get("skills"));
    }

    @GetMapping("/{id}")
    public ApiResult<Object> get(@PathVariable String id) {
        // 首期目录规模很小；通过列表返回并在 Java 适配层做只读筛选。
        Object skills = list().data();
        if (skills instanceof List<?> items) {
            return ApiResult.success(items.stream()
                    .filter(item -> item instanceof Map<?, ?> map && id.equals(String.valueOf(map.get("id"))))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException("SKILL_NOT_FOUND", "Skill 不存在")));
        }
        throw new BusinessException("SKILL_CATALOG_INVALID", "下层 Skill 目录格式无效");
    }

    @PostMapping("/parse-jd")
    public ApiResult<Object> parseJd(@Valid @RequestBody ParseJdRequest request) {
        AgentResponse response = agentGateway.skills(
                request("agent.skills.parse-jd", request.jdText()));
        return ApiResult.success(requiredOutput(response).get("categories"));
    }

    private AgentSkillRequest request(String operation, String question) {
        return new AgentSkillRequest(
                "v1", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                demoUserId, "skill-catalog", operation, question);
    }

    private Map<String, Object> requiredOutput(AgentResponse response) {
        if (response.code() < 100 || response.code() >= 200 || response.output() == null) {
            throw new BusinessException("SKILL_SERVICE_FAILED", "下层 Skill 服务处理失败");
        }
        return response.output();
    }

    public record ParseJdRequest(@NotBlank String jdText) {
    }
}
