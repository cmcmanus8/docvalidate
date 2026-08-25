package com.docvalidate.service.api;

import com.docvalidate.service.config.DocValidateProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.util.regex.Pattern;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guards the upload endpoint before Spring buffers the body into a byte array. An
 * oversized upload is rejected on its Content-Length; one that declines to declare a
 * length at all is rejected outright, because there is nothing left to check it against
 * once the bytes are already in memory. A presigned S3 PUT requires a length too.
 */
@Component
class UploadSizeLimitFilter extends OncePerRequestFilter {

    private static final Pattern UPLOAD_PATH =
            Pattern.compile("/api/v1/validations/[^/]+/content/?");

    private final long maxBytes;
    private final ObjectMapper json;

    UploadSizeLimitFilter(DocValidateProperties properties, ObjectMapper json) {
        this.maxBytes = properties.maxUploadSize().toBytes();
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.PUT.matches(request.getMethod())
                || !UPLOAD_PATH.matcher(request.getRequestURI()).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        long declared = request.getContentLengthLong();

        if (declared < 0) {
            problem(response, HttpStatus.LENGTH_REQUIRED, ProblemCode.LENGTH_REQUIRED,
                    "Length required", "Uploads must declare a Content-Length");
            return;
        }
        if (declared > maxBytes) {
            problem(response, HttpStatus.PAYLOAD_TOO_LARGE, ProblemCode.PAYLOAD_TOO_LARGE,
                    "Upload too large", "Upload exceeds the " + maxBytes + " byte limit");
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Serialised rather than hand-formatted. A filter runs before the advice, so the body
     * has to be built here - but building it with string interpolation is how an unescaped
     * quote in a detail message turns a 413 into a parse error on the client.
     */
    private void problem(HttpServletResponse response, HttpStatus status, ProblemCode code,
                         String title, String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(code.type()));
        problem.setTitle(title);
        problem.setProperty("code", code.name());

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getWriter(), problem);
    }
}
