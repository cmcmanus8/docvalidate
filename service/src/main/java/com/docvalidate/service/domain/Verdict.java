package com.docvalidate.service.domain;

public enum Verdict {

    PASS,

    /** The document was read and judged unacceptable. */
    FAIL,

    /** The document was never judged: something failed while processing it. */
    ERROR
}
