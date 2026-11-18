package com.proofix.runner.scan;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The only place the runner starts a process. It can run exactly three read-only git commands
 * ({@link Command}); arguments are fixed and no shell is involved.
 */
public class GitProcess {

    /** The complete allow-list of git invocations. */
    public enum Command {
        LS_FILES(10, "ls-files", "--cached", "--others", "--exclude-standard", "-z"),
        REV_PARSE_HEAD(5, "rev-parse", "HEAD"),
        STATUS_PORCELAIN(5, "status", "--porcelain");

        final int timeoutSeconds;
        final List<String> args;

        Command(int timeoutSeconds, String... args) {
            this.timeoutSeconds = timeoutSeconds;
            this.args = List.of(args);
        }
    }

    public record Result(int exitCode, byte[] stdout, String stderr) {}

    public static final class GitException extends Exception {
        public GitException(String message) {
            super(message);
        }
    }

    private static final int MAX_STDOUT = 64 * 1024 * 1024;
    private static final int MAX_STDERR = 64 * 1024;

    private final String executable;

    public GitProcess() {
        this("git");
    }

    /** Tests pass a non-existent executable to simulate a missing git. */
    public GitProcess(String executable) {
        this.executable = executable;
    }

    public Result run(Path root, Command command) throws GitException {
        List<String> cmd = new ArrayList<>();
        cmd.add(executable);
        // Disable fsmonitor: a repository's config could otherwise make git start a hook program.
        cmd.add("-c");
        cmd.add("core.fsmonitor=false");
        cmd.add("-C");
        cmd.add(root.toString());
        cmd.addAll(command.args);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        Map<String, String> env = pb.environment();
        env.remove("GIT_DIR");
        env.remove("GIT_WORK_TREE");
        env.remove("GIT_INDEX_FILE");
        env.put("GIT_OPTIONAL_LOCKS", "0");
        env.put("GIT_TERMINAL_PROMPT", "0");

        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new GitException("git could not be started");
        }
        try {
            p.getOutputStream().close();
        } catch (IOException ignored) {
            // stdin already closed
        }

        BoundedReader out = new BoundedReader(p.getInputStream(), MAX_STDOUT);
        BoundedReader err = new BoundedReader(p.getErrorStream(), MAX_STDERR);
        Thread tOut = start(out, "git-stdout");
        Thread tErr = start(err, "git-stderr");
        try {
            if (!p.waitFor(command.timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new GitException("git timed out");
            }
            tOut.join(2000);
            tErr.join(2000);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new GitException("interrupted");
        }
        if (out.overflow) {
            throw new GitException("git output too large");
        }
        return new Result(p.exitValue(), out.bytes(), new String(err.bytes(), StandardCharsets.UTF_8));
    }

    private static Thread start(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** Drains a stream completely (so the child never blocks) while keeping at most max bytes. */
    private static final class BoundedReader implements Runnable {
        private final InputStream in;
        private final int max;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        volatile boolean overflow;

        BoundedReader(InputStream in, int max) {
            this.in = in;
            this.max = max;
        }

        @Override
        public void run() {
            byte[] chunk = new byte[8192];
            try (in) {
                int n;
                while ((n = in.read(chunk)) != -1) {
                    synchronized (buf) {
                        int room = max - buf.size();
                        if (n > room) {
                            overflow = true;
                            if (room > 0) buf.write(chunk, 0, room);
                        } else {
                            buf.write(chunk, 0, n);
                        }
                    }
                }
            } catch (IOException ignored) {
                // process ended or was killed
            }
        }

        byte[] bytes() {
            synchronized (buf) {
                return buf.toByteArray();
            }
        }
    }
}
