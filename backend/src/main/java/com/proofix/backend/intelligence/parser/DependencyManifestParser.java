package com.proofix.backend.intelligence.parser;

import com.proofix.backend.intelligence.model.DependencyManifestParseResult;

public interface DependencyManifestParser {

    boolean supports(String repositoryRelativePath);

    DependencyManifestParseResult parse(
            String repositoryRelativePath,
            String persistedContent);
}
