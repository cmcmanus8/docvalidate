package com.docvalidate.service.api;

public class MissingFilenameException extends RuntimeException {

    public MissingFilenameException() {
        super("Content-Disposition must carry a filename, e.g. attachment; filename=\"invoice.pdf\"");
    }
}
