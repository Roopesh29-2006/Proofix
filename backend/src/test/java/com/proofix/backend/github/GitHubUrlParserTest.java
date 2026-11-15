package com.proofix.backend.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class GitHubUrlParserTest {

    private final GitHubUrlParser parser = new GitHubUrlParser();

    @Test
    void parsesRepositoryUrlWithGitSuffixAndTrailingSlash() {
        GitHubUrlParser.RepositoryCoordinates coordinates =
                parser.parse("https://github.com/acme/demo.git/");

        assertEquals("acme", coordinates.owner());
        assertEquals("demo", coordinates.repository());
    }

    @Test
    void rejectsNonGitHubHost() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse("https://gitlab.com/acme/demo"));
        assertEquals(
                "Only valid github.com repository URLs are supported",
                exception.getMessage());
    }

    @Test
    void rejectsQueryParametersAndFragments() {
        IllegalArgumentException queryException = assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse("https://github.com/acme/demo?tab=readme"));
        IllegalArgumentException fragmentException = assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse("https://github.com/acme/demo#readme"));
        assertEquals(
                "Query parameters and fragments are not supported",
                queryException.getMessage());
        assertEquals(
                "Query parameters and fragments are not supported",
                fragmentException.getMessage());
    }

    @Test
    void rejectsEncodedRepositoryPath() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse("https://github.com/acme%2Fteam/demo"));
        assertEquals(
                "Invalid GitHub repository path",
                exception.getMessage());
    }
}
