package com.proofix.runner.detect;

/** Result of reading a stack's marker files: whether it is confirmed and which role it plays. */
public record Confirmation(boolean confirmed, String role, String reason) {

    public static final String MARKER_UNPARSEABLE = "MARKER_UNPARSEABLE";
    public static final String MARKER_TOO_LARGE = "MARKER_TOO_LARGE";
    public static final String MARKER_UNREADABLE = "MARKER_UNREADABLE";
}
