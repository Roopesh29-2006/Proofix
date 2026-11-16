package com.proofix.backend.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Run.moveTo is package-private, so the compiler keeps other packages out.
 * This guards the rest of the run package: only RunStateMachine may call it.
 */
class RunStatusMutationGuardTest {

    private static final Path MAIN = Path.of("src", "main", "java");
    private static final Pattern MOVE_TO_CALL = Pattern.compile("\\.moveTo\\s*\\(");

    @Test
    void onlyRunStateMachineCallsMoveTo() throws IOException {
        List<String> callers;
        try (Stream<Path> files = Files.walk(MAIN)) {
            callers = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(RunStatusMutationGuardTest::callsMoveTo)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        }
        assertEquals(List.of("RunStateMachine.java"), callers);
    }

    private static boolean callsMoveTo(Path file) {
        try {
            return MOVE_TO_CALL.matcher(Files.readString(file)).find();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
