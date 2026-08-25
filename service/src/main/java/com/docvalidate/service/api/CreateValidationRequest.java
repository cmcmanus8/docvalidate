package com.docvalidate.service.api;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Optional. Declaring the document up front lets the upload omit Content-Disposition and
 * lets the service reject bytes that are not what the request said they would be.
 */
public record CreateValidationRequest(

        @Size(min = 1, max = 512, message = "filename must be between 1 and 512 characters")
        @Pattern(regexp = "[^/\\\\]+", message = "filename must not contain a path")
        String filename,

        @Size(max = 255)
        @Pattern(regexp = "[\\w.+-]+/[\\w.+-]+", message = "contentType must be a media type, e.g. application/pdf")
        String contentType) {
}
