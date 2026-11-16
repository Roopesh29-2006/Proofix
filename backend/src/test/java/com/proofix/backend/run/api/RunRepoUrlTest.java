package com.proofix.backend.run.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.proofix.backend.exception.BadRequestException;

class RunRepoUrlTest {

    @Test
    void acceptsCanonicalUrl() {
        assertEquals("https://github.com/octo/demo",
                RunRepoUrl.normalize("https://github.com/octo/demo"));
    }

    @Test
    void normalizesCaseAndSurroundingWhitespace() {
        // GitHub owner and repository names are case-insensitive.
        assertEquals("https://github.com/octo-org/my.repo_1",
                RunRepoUrl.normalize("  https://github.com/Octo-Org/My.Repo_1 \n"));
    }

    @Test
    void acceptsMaximumLengthNames() {
        String owner = "a".repeat(39);
        String repo = "b".repeat(100);
        assertEquals("https://github.com/" + owner + "/" + repo,
                RunRepoUrl.normalize("https://github.com/" + owner + "/" + repo));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void rejectsMissingUrl(String url) {
        assertRejected(url);
    }

    @Test
    void rejectsOverlongInput() {
        assertRejected("https://github.com/octo/" + "a".repeat(200));
    }

    @ParameterizedTest(name = "scheme/host: {0}")
    @ValueSource(strings = {
            "http://github.com/octo/demo",
            "HTTPS://github.com/octo/demo",
            "ftp://github.com/octo/demo",
            "github.com/octo/demo",
            "//github.com/octo/demo",
            "https://www.github.com/octo/demo",
            "https://gist.github.com/octo/demo",
            "https://GitHub.com/octo/demo",
            "https://github.com.evil.example/octo/demo",
            "https://evil.example/octo/demo",
            "https://github.com:443/octo/demo",
    })
    void rejectsOtherSchemesHostsAndPorts(String url) {
        assertRejected(url);
    }

    @ParameterizedTest(name = "userinfo: {0}")
    @ValueSource(strings = {
            "https://user@github.com/octo/demo",
            "https://user:pass@github.com/octo/demo",
            "https://github.com@evil.example/octo/demo",
    })
    void rejectsUserinfo(String url) {
        assertRejected(url);
    }

    @ParameterizedTest(name = "query/fragment: {0}")
    @ValueSource(strings = {
            "https://github.com/octo/demo?tab=readme",
            "https://github.com/octo/demo?",
            "https://github.com/octo/demo#readme",
            "https://github.com/octo/demo#",
    })
    void rejectsQueryAndFragment(String url) {
        assertRejected(url);
    }

    @ParameterizedTest(name = "path: {0}")
    @ValueSource(strings = {
            "https://github.com/",
            "https://github.com/octo",
            "https://github.com/octo/",
            "https://github.com/octo/demo/",
            "https://github.com/octo/demo/tree/main",
            "https://github.com/octo//demo",
            "https://github.com//octo/demo",
            "https://github.com/octo/demo.git",
            "https://github.com/octo/demo%2Fx",
            "https://github.com/octo%20x/demo",
            "https://github.com/octo\\demo",
            "https://github.com/octo/de mo",
            "https://github.com/octo/démo",
    })
    void rejectsExtraPathEncodingAndBadCharacters(String url) {
        assertRejected(url);
    }

    @ParameterizedTest(name = "owner: {0}")
    @ValueSource(strings = {
            "-octo", "octo-", "oc_to", "oc.to",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", // 40 chars
    })
    void rejectsInvalidOwner(String owner) {
        assertRejected("https://github.com/" + owner + "/demo");
    }

    @ParameterizedTest(name = "repo: {0}")
    @ValueSource(strings = {".", "..", "de$mo", "de:mo", "de@mo"})
    void rejectsInvalidRepository(String repo) {
        assertRejected("https://github.com/octo/" + repo);
    }

    @Test
    void rejectsOverlongRepository() {
        assertRejected("https://github.com/octo/" + "b".repeat(101));
    }

    private static void assertRejected(String url) {
        assertThrows(BadRequestException.class, () -> RunRepoUrl.normalize(url), url);
    }
}
