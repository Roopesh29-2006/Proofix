package com.proofix.backend.run;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

final class RunTime {

    private RunTime() {
    }

    /** UTC now, truncated to microseconds so it round-trips through PostgreSQL. */
    static OffsetDateTime now(Clock clock) {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS);
    }
}
