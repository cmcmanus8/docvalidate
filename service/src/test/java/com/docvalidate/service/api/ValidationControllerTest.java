package com.docvalidate.service.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.docvalidate.service.application.ContentMismatchException;
import com.docvalidate.service.application.DeclaredTypeMismatchException;
import com.docvalidate.service.application.MissingFilenameException;
import com.docvalidate.service.application.CreateResult;
import com.docvalidate.service.application.UploadOutcome;
import com.docvalidate.service.application.ValidationNotFoundException;
import com.docvalidate.service.application.ValidationService;
import com.docvalidate.service.config.DocValidateProperties;
import com.docvalidate.service.domain.ValidationRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ValidationController.class)
@EnableConfigurationProperties(DocValidateProperties.class)
@TestPropertySource(properties = {
        "docvalidate.upload-window=PT15M",
        "docvalidate.max-upload-size=1KB",
        "docvalidate.storage-root=./.data"
})
class ValidationControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-25T09:00:00Z");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ValidationService validations;

    private static ValidationRequest pendingRequest() {
        return ValidationRequest.create(null, NOW, Duration.ofMinutes(15));
    }

    @Test
    void createReturns201WithAnAbsoluteUploadUrl() throws Exception {
        ValidationRequest request = pendingRequest();
        when(validations.create(null, null, null)).thenReturn(new CreateResult(request, false));

        mvc.perform(post("/api/v1/validations"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION,
                        "http://localhost/api/v1/validations/" + request.getId() + "/content"))
                .andExpect(jsonPath("$.requestId").value(request.getId().toString()))
                .andExpect(jsonPath("$.status").value("PENDING_UPLOAD"))
                .andExpect(jsonPath("$.uploadUrl")
                        .value("http://localhost/api/v1/validations/" + request.getId() + "/content"));
    }

    @Test
    void aReplayedIdempotencyKeyReturns200AndCreatesNothing() throws Exception {
        ValidationRequest request = pendingRequest();
        when(validations.create("key-1", null, null)).thenReturn(new CreateResult(request, true));

        mvc.perform(post("/api/v1/validations").header("Idempotency-Key", "key-1"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.requestId").value(request.getId().toString()));
    }

    @Test
    void uploadReturns202AndTheQueuedView() throws Exception {
        ValidationRequest request = pendingRequest();
        request.attachDocument("invoice.pdf", "application/pdf", 5L, "a".repeat(64), "key", NOW);
        when(validations.upload(eq(request.getId()), eq("invoice.pdf"), eq("application/pdf"), any()))
                .thenReturn(UploadOutcome.ACCEPTED);
        when(validations.get(request.getId())).thenReturn(request);

        mvc.perform(put("/api/v1/validations/{id}/content", request.getId())
                        .contentType(MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"invoice.pdf\"")
                        .content("hello"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.document.filename").value("invoice.pdf"));
    }

    @Test
    void aByteIdenticalReuploadReturns200() throws Exception {
        ValidationRequest request = pendingRequest();
        request.attachDocument("invoice.pdf", "application/pdf", 5L, "a".repeat(64), "key", NOW);
        when(validations.upload(any(), any(), any(), any())).thenReturn(UploadOutcome.ALREADY_ACCEPTED);
        when(validations.get(request.getId())).thenReturn(request);

        mvc.perform(put("/api/v1/validations/{id}/content", request.getId())
                        .contentType(MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"invoice.pdf\"")
                        .content("hello"))
                .andExpect(status().isOk());
    }

    @Test
    void anUploadWithNoFilenameAnywhereIs400() throws Exception {
        // Neither a Content-Disposition nor a declaration on the request: the service is
        // the layer that can tell, so it decides and the advice maps it.
        when(validations.upload(any(), any(), any(), any())).thenThrow(new MissingFilenameException());

        mvc.perform(put("/api/v1/validations/{id}/content", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_PDF)
                        .content("hello"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void differentBytesForAnAcceptedRequestAre409ContentMismatch() throws Exception {
        UUID requestId = UUID.randomUUID();
        when(validations.upload(any(), any(), any(), any())).thenThrow(new ContentMismatchException(requestId));

        mvc.perform(put("/api/v1/validations/{id}/content", requestId)
                        .contentType(MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"invoice.pdf\"")
                        .content("different"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTENT_MISMATCH"))
                .andExpect(jsonPath("$.requestId").value(requestId.toString()))
                .andExpect(jsonPath("$.type").value("https://docvalidate.dev/problems/content-mismatch"));
    }

    @Test
    void anUploadOverTheSizeLimitIsRejectedOnItsContentLength() throws Exception {
        mvc.perform(put("/api/v1/validations/{id}/content", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"invoice.pdf\"")
                        .content("x".repeat(2048)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));

        verifyNoInteractions(validations);
    }

    @Test
    void getReturns404WithACodeTheSdkCanBranchOn() throws Exception {
        UUID requestId = UUID.randomUUID();
        when(validations.get(requestId)).thenThrow(new ValidationNotFoundException(requestId));

        mvc.perform(get("/api/v1/validations/{id}", requestId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VALIDATION_NOT_FOUND"))
                .andExpect(jsonPath("$.requestId").value(requestId.toString()));
    }

    @Test
    void aMalformedRequestIdIs400RatherThan500() throws Exception {
        mvc.perform(get("/api/v1/validations/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void anUploadAfterTheWindowClosedIs409RequestExpired() throws Exception {
        UUID requestId = UUID.randomUUID();
        when(validations.upload(any(), any(), any(), any())).thenReturn(UploadOutcome.EXPIRED);

        mvc.perform(put("/api/v1/validations/{id}/content", requestId)
                        .contentType(MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"invoice.pdf\"")
                        .content("hello"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_EXPIRED"));
    }

    @Test
    void aMalformedContentDispositionIs400RatherThan500() throws Exception {
        mvc.perform(put("/api/v1/validations/{id}/content", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''%zz")
                        .content("hello"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(validations);
    }

    @Test
    void anUnknownPathIs404NotAnInternalError() throws Exception {
        mvc.perform(get("/api/v1/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void theWrongMethodIs405AndKeepsItsAllowHeader() throws Exception {
        mvc.perform(post("/api/v1/validations/{id}", UUID.randomUUID()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists(HttpHeaders.ALLOW))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void aCreateBodyDeclaringTheDocumentIsPassedThrough() throws Exception {
        ValidationRequest request = pendingRequest();
        when(validations.create(null, "invoice.pdf", "application/pdf"))
                .thenReturn(new CreateResult(request, false));

        mvc.perform(post("/api/v1/validations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filename\":\"invoice.pdf\",\"contentType\":\"application/pdf\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestId").value(request.getId().toString()));
    }

    @Test
    void aFilenameWithAPathIsRejectedByBeanValidation() throws Exception {
        mvc.perform(post("/api/v1/validations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filename\":\"../../etc/passwd\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("filename"))
                .andExpect(jsonPath("$.errors[0].message").value("filename must not contain a path"));

        verifyNoInteractions(validations);
    }

    @Test
    void aContentTypeThatIsNotAMediaTypeIsRejected() throws Exception {
        mvc.perform(post("/api/v1/validations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"not a media type\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void anUploadMayOmitContentDispositionAndLetTheServiceUseTheDeclaredFilename() throws Exception {
        ValidationRequest request = pendingRequest();
        request.attachDocument("declared.pdf", "application/pdf", 5L, "a".repeat(64), "key", NOW);
        when(validations.upload(eq(request.getId()), eq(null), eq("application/pdf"), any()))
                .thenReturn(UploadOutcome.ACCEPTED);
        when(validations.get(request.getId())).thenReturn(request);

        mvc.perform(put("/api/v1/validations/{id}/content", request.getId())
                        .contentType(MediaType.APPLICATION_PDF)
                        .content("hello"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.document.filename").value("declared.pdf"));
    }

    @Test
    void bytesThatContradictTheDeclarationAre409() throws Exception {
        UUID requestId = UUID.randomUUID();
        when(validations.upload(any(), any(), any(), any()))
                .thenThrow(new DeclaredTypeMismatchException(requestId, "application/pdf", "image/png"));

        mvc.perform(put("/api/v1/validations/{id}/content", requestId)
                        .contentType(MediaType.IMAGE_PNG)
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"x.png\"")
                        .content("hello"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DECLARED_TYPE_MISMATCH"));
    }
}
