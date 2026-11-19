package com.proofix.runner.facts.model;

import java.util.Comparator;

/**
 * One element of the facts.json "facts" array: a config fact or an env var fact. Both serialize as
 * plain objects (no type field); env var facts are recognised by their "env." name prefix.
 */
public sealed interface FactEntry permits Fact, EnvVarFact {

    String name();

    /** Sort by module, name, profile, value (nulls first); env var facts have only a name. */
    Comparator<FactEntry> ORDER = Comparator
            .comparing(FactEntry::sortModule, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(FactEntry::name)
            .thenComparing(FactEntry::sortProfile, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(FactEntry::sortValue, Comparator.nullsFirst(Comparator.naturalOrder()));

    private static String sortModule(FactEntry e) {
        return e instanceof Fact f ? f.module() : null;
    }

    private static String sortProfile(FactEntry e) {
        return e instanceof Fact f ? f.profile() : null;
    }

    private static String sortValue(FactEntry e) {
        return e instanceof Fact f ? f.value() : null;
    }
}
