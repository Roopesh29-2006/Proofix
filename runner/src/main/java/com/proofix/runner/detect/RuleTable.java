package com.proofix.runner.detect;

import java.util.List;

/**
 * The rule table, as data. Within one ecosystem the FIRST matching rule wins, so more specific
 * rules come first (nextjs before node). Rules of different ecosystems never compete.
 */
public final class RuleTable {
    private RuleTable() {}

    public static final List<Rule> RULES = List.of(
            new Rule("nextjs", "node", "nextjs",
                    List.of(List.of("package.json"),
                            List.of("next.config.js", "next.config.mjs", "next.config.ts"))),
            new Rule("node", "node", "node", List.of(List.of("package.json"))),
            new Rule("maven", "jvm-maven", "java-maven", List.of(List.of("pom.xml"))),
            new Rule("gradle", "jvm-gradle", "java-gradle",
                    List.of(List.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"))),
            new Rule("python", "python", "python",
                    List.of(List.of("pyproject.toml", "requirements.txt", "Pipfile", "setup.py"))),
            new Rule("go", "go", "go", List.of(List.of("go.mod"))),
            new Rule("ruby", "ruby", "ruby", List.of(List.of("Gemfile"))),
            new Rule("php", "php", "php", List.of(List.of("composer.json"))),
            new Rule("rust", "rust", "rust", List.of(List.of("Cargo.toml"))),
            new Rule("dotnet", "dotnet", "dotnet", List.of(List.of("*.csproj", "*.sln"))));

    public static boolean isAnyMarker(String fileName) {
        for (Rule r : RULES) {
            if (r.isMarker(fileName)) return true;
        }
        return false;
    }
}
