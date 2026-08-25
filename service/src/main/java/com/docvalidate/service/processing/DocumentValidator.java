package com.docvalidate.service.processing;

import com.docvalidate.service.config.DocValidateProperties;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * A stand-in for whatever the real validation engine would be. Deterministic and
 * dependency-free on purpose: the interesting part of this service is the lifecycle
 * around it, and a stub that reads the same document twice and disagrees with itself
 * would make every test above it untrustworthy.
 */
@Component
public class DocumentValidator {

    private final Set<String> allowedContentTypes;

    public DocumentValidator(DocValidateProperties properties) {
        this.allowedContentTypes = properties.allowedContentTypes();
    }

    public ValidationOutcome validate(String filename, String contentType, byte[] content) {
        if (content.length == 0) {
            return ValidationOutcome.fail("EMPTY_DOCUMENT");
        }
        if (!allowedContentTypes.contains(contentType)) {
            return ValidationOutcome.fail("UNSUPPORTED_CONTENT_TYPE");
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("documentType", documentType(filename));
        fields.put("contentType", contentType);
        fields.put("sizeBytes", content.length);
        fields.put("lineCount", countLines(content));
        return ValidationOutcome.pass(fields);
    }

    private static String documentType(String filename) {
        String name = filename.toLowerCase(Locale.ROOT);
        if (name.contains("invoice")) {
            return "INVOICE";
        }
        if (name.contains("receipt")) {
            return "RECEIPT";
        }
        if (name.contains("statement")) {
            return "STATEMENT";
        }
        return "UNKNOWN";
    }

    private static long countLines(byte[] content) {
        long newlines = 0;
        for (byte b : content) {
            if (b == '\n') {
                newlines++;
            }
        }
        return content[content.length - 1] == '\n' ? newlines : newlines + 1;
    }
}
