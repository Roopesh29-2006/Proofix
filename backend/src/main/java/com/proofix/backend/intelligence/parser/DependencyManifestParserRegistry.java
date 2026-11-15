package com.proofix.backend.intelligence.parser;

import java.util.List;

import com.proofix.backend.intelligence.model.DependencyManifestParseResult;
import com.proofix.backend.intelligence.model.ParseStatus;
import org.springframework.stereotype.Component;

@Component
public class DependencyManifestParserRegistry {

    private final List<DependencyManifestParser> parsers;

    public DependencyManifestParserRegistry(
            List<DependencyManifestParser> parsers) {
        this.parsers = List.copyOf(parsers);
    }

    public DependencyManifestParseResult parse(
            String repositoryRelativePath,
            String persistedContent) {

        List<DependencyManifestParser> matchingParsers = parsers.stream()
                .filter(parser -> parser.supports(repositoryRelativePath))
                .toList();

        if (matchingParsers.isEmpty()) {
            return new DependencyManifestParseResult(
                    repositoryRelativePath,
                    ParseStatus.UNSUPPORTED_PATH,
                    List.of(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of(
                            "No registered dependency manifest parser supports "
                                    + "the supplied file path."));
        }

        if (matchingParsers.size() > 1) {
            throw new IllegalStateException(
                    "Ambiguous dependency manifest parser selection for path '"
                            + repositoryRelativePath
                            + "': "
                            + matchingParsers.size()
                            + " parsers support the path.");
        }

        return matchingParsers.get(0).parse(
                repositoryRelativePath,
                persistedContent);
    }
}
