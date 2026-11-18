package io.iwork.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiFailureFilterTest {
    @Test
    @DisplayName("Unexpected API failures return a safe JSON envelope and HTTP 500")
    void returnsSafeEnvelopeForUnexpectedFailure() throws Exception {
        var availability = mock(ApplicationAvailability.class);
        when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
        var filter = new SecurityConfiguration().dependencyFailures(availability).getFilter();
        var response = new MockHttpServletResponse();
        filter.doFilter(
                new MockHttpServletRequest("GET", "/api/conversations"),
                response,
                (request, result) -> {
                    throw new ServletException("private failure detail");
                });
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString())
                .isEqualTo("{\"success\":false,\"data\":null,\"error\":\"internal_error\"}");
    }
}
