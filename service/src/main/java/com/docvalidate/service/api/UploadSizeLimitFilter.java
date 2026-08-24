package com.docvalidate.service.api;

import com.docvalidate.service.config.DocValidateProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects an oversized upload on its Content-Length, before the body is read into memory.
 * The service-layer check stays as well, because a chunked request arrives without one.
 */
@Component
class UploadSizeLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;

    UploadSizeLimitFilter(DocValidateProperties properties) {
        this.maxBytes = properties.maxUploadSize().toBytes();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (request.getContentLengthLong() > maxBytes) {
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("""
                    {"type":"%s","title":"Upload too large","status":413,\
                    "detail":"Upload exceeds the %d byte limit","code":"%s"}"""
                    .formatted(ProblemCode.PAYLOAD_TOO_LARGE.type(), maxBytes, ProblemCode.PAYLOAD_TOO_LARGE.name()));
            return;
        }
        chain.doFilter(request, response);
    }
}
