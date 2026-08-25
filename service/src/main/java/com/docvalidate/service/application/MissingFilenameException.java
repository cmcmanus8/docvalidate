package com.docvalidate.service.application;

public class MissingFilenameException extends RuntimeException {

    public MissingFilenameException() {
        super("No filename: declare one when creating the request, or send Content-Disposition "
                + "with the upload, e.g. attachment; filename=\"invoice.pdf\"");
    }
}
