package com.proofix.backend.run;

import com.proofix.backend.exception.ConflictException;

/** A status move that the transition table does not allow. Maps to HTTP 409. */
public class IllegalRunTransitionException extends ConflictException {

    public IllegalRunTransitionException(RunStatus from, RunStatus to) {
        super("Illegal run transition: " + from + " -> " + to);
    }
}
