package com.docvalidate.service.domain;

public enum Verdict {

    VALID,

    /** The document was read and judged unacceptable. */
    INVALID,

    /** The document was never judged: something failed while processing it. */
    ERROR
}
