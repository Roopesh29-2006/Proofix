package com.proofix.runner.testutil;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.proofix.runner.cli.Cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Runs the real CLI end to end and captures facts.json, stdout and stderr. */
public final class RunnerHarness {
    private RunnerHarness() {}

    private static final ObjectMapper JSON = new ObjectMapper();

    public record Outcome(int exit, byte[] bytes, JsonNode json, String stdout, String stderr) {

        public JsonNode stack(String id) {
            for (JsonNode s : json.get("stacks")) {
                if (s.get("id").asText().equals(id)) return s;
            }
            return null;
        }

        public List<String> stackIds() {
            List<String> ids = new ArrayList<>();
            json.get("stacks").forEach(s -> ids.add(s.get("id").asText()));
            return ids;
        }

        public List<String> warningCodes() {
            List<String> codes = new ArrayList<>();
            json.get("warnings").forEach(w -> codes.add(w.get("code").asText()));
            return codes;
        }

        public boolean hasWarning(String code, String path) {
            for (JsonNode w : json.get("warnings")) {
                if (w.get("code").asText().equals(code)
                        && (path == null || (w.hasNonNull("path") && w.get("path").asText().equals(path)))) {
                    return true;
                }
            }
            return false;
        }

        public String everything() {
            return new String(bytes, StandardCharsets.UTF_8) + stdout + stderr;
        }
    }

    public static Outcome scan(Cli cli, Path repo, Path outDir, String... extra) {
        try {
            Files.createDirectories(outDir);
            Path out = Files.createTempFile(outDir, "facts", ".json");
            List<String> args = new ArrayList<>(List.of("scan", repo.toString(), "--out", out.toString()));
            args.addAll(List.of(extra));
            ByteArrayOutputStream so = new ByteArrayOutputStream();
            ByteArrayOutputStream se = new ByteArrayOutputStream();
            int exit = cli.run(args.toArray(String[]::new),
                    new PrintStream(so, true, StandardCharsets.UTF_8),
                    new PrintStream(se, true, StandardCharsets.UTF_8));
            byte[] bytes = Files.readAllBytes(out);
            JsonNode json = exit == 0 ? JSON.readTree(bytes) : null;
            return new Outcome(exit, bytes, json, so.toString(StandardCharsets.UTF_8), se.toString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Outcome scan(Path repo, Path outDir, String... extra) {
        return scan(new Cli(), repo, outDir, extra);
    }
}
