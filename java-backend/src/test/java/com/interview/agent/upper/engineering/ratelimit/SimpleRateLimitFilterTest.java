package com.interview.agent.upper.engineering.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.agent.upper.engineering.web.RequestIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleRateLimitFilterTest {
    @Test
    void rateLimitUsesTheSharedErrorEnvelope() throws Exception {
        SimpleRateLimitFilter filter = new SimpleRateLimitFilter(1, new ObjectMapper());
        MockHttpServletRequest first = request();
        filter.doFilter(first, new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletRequest second = request();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(second, response, new MockFilterChain());

        assertEquals(429, response.getStatus());
        assertTrue(response.getContentAsString().contains("RATE_LIMIT_EXCEEDED"));
        assertTrue(response.getContentAsString().contains("rate-request"));
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/interviews");
        request.setRemoteAddr("127.0.0.1");
        request.setAttribute(RequestIdFilter.ATTRIBUTE, "rate-request");
        return request;
    }
}
