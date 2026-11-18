package com.proofix.runner;

import com.proofix.runner.secrets.SecretFiles;
import com.proofix.runner.testutil.Fixtures;
import com.proofix.runner.testutil.RunnerHarness;
import com.proofix.runner.testutil.RunnerHarness.Outcome;
import com.proofix.runner.testutil.TestGit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [SEC] Secret files are reported by path only; their contents never reach output or logs. */
class SecretNeverLeaksTest {

    @TempDir
    Path tmp;

    private static String canary() {
        return "SECRET-CANARY-" + UUID.randomUUID();
    }

    @Test
    void envValueNeverAppearsInFactsOrLogsWalkMode() {
        String c1 = canary();
        String c2 = canary();
        Path repo = Fixtures.load("quickcart", tmp);
        Fixtures.write(repo, ".env", "STRIPE_KEY=" + c1 + "\n");
        Fixtures.write(repo, "api/.env.production", "DB_PASSWORD=" + c2 + "\n");
        Fixtures.write(repo, ".env.example", "STRIPE_KEY=\n");
        Outcome o = RunnerHarness.scan(repo, tmp.resolve("out"));
        assertEquals(0, o.exit());
        assertTrue(o.hasWarning("SECRET_FILE_PRESENT", ".env"));
        assertTrue(o.hasWarning("SECRET_FILE_PRESENT", "api/.env.production"));
        assertFalse(o.hasWarning("SECRET_FILE_PRESENT", ".env.example"));
        assertFalse(o.everything().contains(c1), "canary leaked");
        assertFalse(o.everything().contains(c2), "canary leaked");
        assertFalse(o.everything().contains("STRIPE_KEY"), "even the key name must not leak");
    }

    @Test
    void envValueNeverAppearsInGitMode() {
        TestGit.requireGit();
        String c = canary();
        Path repo = Fixtures.load("express-api", tmp);
        Fixtures.write(repo, ".env", "TOKEN=" + c + "\n");
        TestGit.initAndCommitAll(repo);
        Outcome o = RunnerHarness.scan(repo, tmp.resolve("out"));
        assertEquals("git", o.json().get("repo").get("fileSource").asText());
        assertTrue(o.hasWarning("SECRET_FILE_PRESENT", ".env"));
        assertFalse(o.everything().contains(c));
    }

    @Test
    void upperAndMixedCaseEnvFilesAreFlaggedAndNeverRead() {
        String c1 = canary();
        String c2 = canary();
        Path repo = Fixtures.load("express-api", tmp);
        Fixtures.write(repo, ".ENV", "A=" + c1 + "\n");
        Fixtures.write(repo, "config/.Env.Local", "B=" + c2 + "\n");
        Outcome o = RunnerHarness.scan(repo, tmp.resolve("out"));
        assertEquals(0, o.exit());
        assertTrue(o.hasWarning("SECRET_FILE_PRESENT", ".ENV"));
        assertTrue(o.hasWarning("SECRET_FILE_PRESENT", "config/.Env.Local"));
        assertFalse(o.everything().contains(c1));
        assertFalse(o.everything().contains(c2));
    }

    @Test
    void caseInsensitiveSecretNames() {
        for (String n : new String[] {".ENV", ".Env", ".eNv", ".ENV.LOCAL", ".Env.Production"}) {
            assertTrue(SecretFiles.isSecretName(n), n);
        }
        for (String n : new String[] {".ENV.EXAMPLE", ".Env.Sample", ".env.TEMPLATE", ".ENVRC"}) {
            assertFalse(SecretFiles.isSecretName(n), n);
        }
    }

    @Test
    void secretNameRules() {
        assertTrue(SecretFiles.isSecretName(".env"));
        assertTrue(SecretFiles.isSecretName(".env.local"));
        assertTrue(SecretFiles.isSecretName(".ENV.Production"));
        assertFalse(SecretFiles.isSecretName(".env.example"));
        assertFalse(SecretFiles.isSecretName(".env.sample"));
        assertFalse(SecretFiles.isSecretName(".env.template"));
        assertFalse(SecretFiles.isSecretName(".envrc"));
        assertFalse(SecretFiles.isSecretName("app.env"));
    }
}
