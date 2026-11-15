package com.proofix.backend.intelligence.model;

public enum ParseStatus {
    SUCCESS,
    UNSUPPORTED_PATH,
    NULL_CONTENT,
    EMPTY_CONTENT,
    MALFORMED_XML,
    UNSUPPORTED_NAMESPACE,
    SECURITY_CONFIGURATION_ERROR
}
