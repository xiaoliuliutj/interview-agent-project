package com.interview.agent.upper.api;

import com.interview.agent.upper.agent.AgentGatewayException;
import com.interview.agent.upper.engineering.web.RequestIdFilter;
import com.interview.agent.upper.service.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiExceptionHandlerTest {
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailureController())
            .setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new RequestIdFilter())
            .build();

    @Test
    void returnsStructuredBusinessErrorWithAgentContext() throws Exception {
        mvc.perform(get("/business").header(RequestIdFilter.HEADER, "browser-request"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(RequestIdFilter.HEADER, "browser-request"))
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error.type").value("MODEL_DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.retryable").value(true))
                .andExpect(jsonPath("$.error.requestId").value("agent-request"))
                .andExpect(jsonPath("$.error.runId").value("run-1"))
                .andExpect(jsonPath("$.error.sessionId").value("session-1"))
                .andExpect(jsonPath("$.error.stage").value("EVALUATING"));
    }

    @Test
    void returnsSafeGatewayError() throws Exception {
        mvc.perform(get("/gateway"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.type").value("AGENT_GATEWAY_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.retryable").value(true));
    }

    @Test
    void hidesUnexpectedExceptionDetails() throws Exception {
        mvc.perform(get("/unexpected").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.type").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value("服务器处理失败，请稍后重试"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("database-password"))));
    }

    @RestController
    static class FailureController {
        @GetMapping("/business")
        void business() {
            throw new BusinessException("MODEL_DEPENDENCY_UNAVAILABLE", "上游模型暂时不可用", true,
                    HttpStatus.SERVICE_UNAVAILABLE, "agent-request", "run-1", "session-1", "EVALUATING");
        }

        @GetMapping("/gateway")
        void gateway() {
            throw new AgentGatewayException("connection refused", null, true);
        }

        @GetMapping("/unexpected")
        void unexpected() {
            throw new IllegalStateException("database-password=/secret/path");
        }
    }
}
