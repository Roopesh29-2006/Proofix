package com.proofix.runner.detect;

import com.proofix.runner.scan.model.ScannedFile;

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

    /**
     * A non-marker file whose CONTENT may be read for config facts. {@code dir} is the directory
     * the file must sit in, matched as a trailing path segment sequence ("src/main/resources"
     * matches "src/main/resources" and "api/src/main/resources"); null means any directory.
     */
    public record ContentFile(String pattern, String dir) {}

    private static final String RESOURCES = "src/main/resources";

    public static final List<ContentFile> CONTENT_FILES = List.of(
            new ContentFile("application.yml", RESOURCES),
            new ContentFile("application.yaml", RESOURCES),
            new ContentFile("application.properties", RESOURCES),
            new ContentFile("application-*.yml", RESOURCES),
            new ContentFile("application-*.yaml", RESOURCES),
            new ContentFile("application-*.properties", RESOURCES),
            new ContentFile("Dockerfile", null),
            new ContentFile(".java-version", null));

    public static boolean isContentFile(ScannedFile file) {
        for (ContentFile c : CONTENT_FILES) {
            if (Rule.matches(c.pattern(), file.fileName()) && inDir(file.dir(), c.dir())) return true;
        }
        return false;
    }

    private static boolean inDir(String fileDir, String required) {
        return required == null || fileDir.equals(required) || fileDir.endsWith("/" + required);
    }

    public static boolean isAnyMarker(String fileName) {
        for (Rule r : RULES) {
            if (r.isMarker(fileName)) return true;
        }
        return false;
    }
}
