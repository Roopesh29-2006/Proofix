package com.proofix.backend.runnerkey;

/**
 * A freshly created key together with its plaintext, which exists only here
 * and in the 201 response. {@link #toString()} never shows the plaintext.
 */
public record CreatedRunnerKey(RunnerKey key, String plaintext) {

    @Override
    public String toString() {
        return "CreatedRunnerKey[key=" + key + ", plaintext=<redacted>]";
    }
}
