package com.docvalidate.service.api;

import com.docvalidate.service.application.CreateResult;
import com.docvalidate.service.application.UploadOutcome;
import com.docvalidate.service.application.ValidationService;
import com.docvalidate.service.domain.ValidationRequest;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/validations")
@Validated
public class ValidationController {

    private final ValidationService validations;

    public ValidationController(ValidationService validations) {
        this.validations = validations;
    }

    @PostMapping
    public ResponseEntity<CreateValidationResponse> create(
            @RequestHeader(name = "Idempotency-Key", required = false)
            @Size(max = 255, message = "Idempotency-Key must be at most 255 characters") String idempotencyKey) {

        CreateResult created = validations.create(idempotencyKey);
        ValidationRequest request = created.request();
        URI uploadUrl = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .pathSegment(request.getId().toString(), "content")
                .build()
                .toUri();

        CreateValidationResponse body = new CreateValidationResponse(
                request.getId(), uploadUrl.toString(), request.getStatus(), request.getExpiresAt());

        // A replay created nothing, so it is not a 201 and carries no Location.
        return created.replayed()
                ? ResponseEntity.ok(body)
                : ResponseEntity.created(uploadUrl).body(body);
    }

    /**
     * Raw bytes rather than multipart, with the metadata in the headers a presigned S3
     * PUT would carry anyway. That keeps the SDK's upload call unchanged on the day the
     * uploadUrl starts pointing at a bucket instead of at this service.
     */
    @PutMapping(path = "/{requestId}/content", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<ValidationView> upload(
            @PathVariable UUID requestId,
            @RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType,
            @RequestHeader(name = HttpHeaders.CONTENT_DISPOSITION, required = false) String contentDisposition,
            @RequestBody byte[] content) {

        String filename = filenameFrom(contentDisposition);
        UploadOutcome outcome = validations.upload(requestId, filename, mimeTypeOf(contentType), content);

        HttpStatus status = outcome == UploadOutcome.ACCEPTED ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ValidationView.of(validations.get(requestId)));
    }

    @GetMapping("/{requestId}")
    public ValidationView get(@PathVariable UUID requestId) {
        return ValidationView.of(validations.get(requestId));
    }

    /** Parameters such as charset are dropped: the allowed-type check compares mime types. */
    private static String mimeTypeOf(String contentType) {
        try {
            MediaType parsed = MediaType.parseMediaType(contentType);
            return new MediaType(parsed.getType(), parsed.getSubtype()).toString();
        } catch (InvalidMediaTypeException e) {
            throw new InvalidContentTypeException(contentType);
        }
    }

    private static String filenameFrom(String contentDisposition) {
        if (contentDisposition == null || contentDisposition.isBlank()) {
            throw new MissingFilenameException();
        }
        String filename = ContentDisposition.parse(contentDisposition).getFilename();
        if (filename == null || filename.isBlank()) {
            throw new MissingFilenameException();
        }
        return filename;
    }
}
