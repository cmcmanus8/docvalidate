package com.docvalidate.service.api;

import com.docvalidate.service.application.ContentMismatchException;
import com.docvalidate.service.application.DeclaredTypeMismatchException;
import com.docvalidate.service.application.MissingFilenameException;
import com.docvalidate.service.application.PayloadTooLargeException;
import com.docvalidate.service.application.RequestExpiredException;
import com.docvalidate.service.application.ValidationNotFoundException;
import com.docvalidate.service.domain.IllegalStateTransitionException;
import com.docvalidate.service.storage.StorageException;
import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Every error leaves the service as an RFC 9457 problem with a {@code code} the SDK can
 * switch on. Status alone is not enough: 409 means three different things here.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ValidationNotFoundException.class)
    ProblemDetail handle(ValidationNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, ProblemCode.VALIDATION_NOT_FOUND,
                "Validation request not found", e.getMessage(), e.getRequestId());
    }

    @ExceptionHandler(IllegalStateTransitionException.class)
    ProblemDetail handle(IllegalStateTransitionException e) {
        return problem(HttpStatus.CONFLICT, ProblemCode.INVALID_STATE_TRANSITION,
                "Invalid state transition", e.getMessage(), e.getRequestId());
    }

    @ExceptionHandler(ContentMismatchException.class)
    ProblemDetail handle(ContentMismatchException e) {
        return problem(HttpStatus.CONFLICT, ProblemCode.CONTENT_MISMATCH,
                "Content already accepted", e.getMessage(), e.getRequestId());
    }

    @ExceptionHandler(DeclaredTypeMismatchException.class)
    ProblemDetail handle(DeclaredTypeMismatchException e) {
        return problem(HttpStatus.CONFLICT, ProblemCode.DECLARED_TYPE_MISMATCH,
                "Content type does not match the declaration", e.getMessage(), e.getRequestId());
    }

    @ExceptionHandler(RequestExpiredException.class)
    ProblemDetail handle(RequestExpiredException e) {
        return problem(HttpStatus.CONFLICT, ProblemCode.REQUEST_EXPIRED,
                "Upload window closed", e.getMessage(), e.getRequestId());
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    ProblemDetail handle(PayloadTooLargeException e) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, ProblemCode.PAYLOAD_TOO_LARGE,
                "Upload too large", e.getMessage(), null);
    }

    @ExceptionHandler({MissingFilenameException.class, InvalidContentTypeException.class,
            MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class, ConstraintViolationException.class})
    ProblemDetail handleBadRequest(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, ProblemCode.INVALID_REQUEST,
                "Invalid request", e.getMessage(), null);
    }

    @ExceptionHandler(StorageException.class)
    ProblemDetail handle(StorageException e) {
        log.error("Document storage failed", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ProblemCode.STORAGE_FAILURE,
                "Storage failure", "The document could not be stored", null);
    }

    /**
     * Bean-validation failures name the fields that failed. "Invalid request content" tells
     * a caller nothing they can act on, and the SDK has no way to surface it usefully.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, ProblemCode.INVALID_REQUEST,
                "Invalid request", "One or more fields are invalid", null);
        problem.setProperty("errors", e.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", String.valueOf(error.getDefaultMessage())))
                .toList());
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * Spring's own failures - unknown path, wrong method, unreadable body - are handled by
     * the base class so they keep their status and their Allow header, and only pick up a
     * code on the way out. Without this they would fall into the catch-all below and every
     * 404 would leave here as a 500.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(e, body, headers, status, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            ProblemCode code = codeFor(status);
            problem.setType(java.net.URI.create(code.type()));
            problem.setProperty("code", code.name());
        }
        return response;
    }

    private static ProblemCode codeFor(HttpStatusCode status) {
        if (status.isSameCodeAs(HttpStatus.NOT_FOUND)) {
            return ProblemCode.RESOURCE_NOT_FOUND;
        }
        if (status.isSameCodeAs(HttpStatus.METHOD_NOT_ALLOWED)) {
            return ProblemCode.METHOD_NOT_ALLOWED;
        }
        if (status.isSameCodeAs(HttpStatus.PAYLOAD_TOO_LARGE)) {
            return ProblemCode.PAYLOAD_TOO_LARGE;
        }
        return status.is4xxClientError() ? ProblemCode.INVALID_REQUEST : ProblemCode.INTERNAL_ERROR;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handle(Exception e) {
        log.error("Unhandled exception", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ProblemCode.INTERNAL_ERROR,
                "Internal error", "The request could not be completed", null);
    }

    private static ProblemDetail problem(HttpStatusCode status, ProblemCode code,
                                         String title, String detail, UUID requestId) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(java.net.URI.create(code.type()));
        problem.setTitle(title);
        problem.setProperty("code", code.name());
        if (requestId != null) {
            problem.setProperty("requestId", requestId.toString());
        }
        return problem;
    }
}
