package com.proofix.runner.facts.model;

/** How a fact's value was established. */
public enum FactStatus {
    /** Declared in a file we read. */
    KNOWN,
    /** Not declared; taken from a documented default (reason says which). */
    DEFAULTED,
    /** Could not be established from what we read (reason says why). */
    UNRESOLVED,
    /** Two or more sources disagree and no precedence rule decides; see candidates. */
    CONFLICT
}
