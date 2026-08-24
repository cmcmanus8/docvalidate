package com.docvalidate.service.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.docvalidate.service.config.DocValidateProperties;
import jakarta.servlet.FilterChain;
import java.time.Duration;
import java.util.Set;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

class UploadSizeLimitFilterTest {

    private final UploadSizeLimitFilter filter = new UploadSizeLimitFilter(
            new DocValidateProperties(Duration.ofMinutes(15), DataSize.ofKilobytes(1), Path.of("./.data"),
                    "jobs", Duration.ZERO, Set.of("application/pdf")));

    private static MockHttpServletRequest upload() {
        return new MockHttpServletRequest("PUT", "/api/v1/validations/" + UUID.randomUUID() + "/content");
    }

    @Test
    void rejectsAnUploadThatDeclaresNoLength() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(upload(), response, chain);

        assertThat(response.getStatus()).isEqualTo(411);
        assertThat(response.getContentAsString()).contains("LENGTH_REQUIRED");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void rejectsAnOversizedUploadBeforeTheBodyIsRead() throws Exception {
        MockHttpServletRequest request = upload();
        request.setContent("x".repeat(2048).getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("PAYLOAD_TOO_LARGE");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void letsAnUploadWithinTheLimitThrough() throws Exception {
        MockHttpServletRequest request = upload();
        request.setContent("small".getBytes());
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verify(chain).doFilter(any(), any());
    }

    @Test
    void ignoresEverythingThatIsNotAnUpload() throws Exception {
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/api/v1/validations/" + UUID.randomUUID());
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(get, new MockHttpServletResponse(), chain);

        verify(chain).doFilter(any(), any());
    }

    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
