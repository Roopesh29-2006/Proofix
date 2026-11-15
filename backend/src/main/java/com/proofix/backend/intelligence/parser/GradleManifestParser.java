package com.proofix.backend.intelligence.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.proofix.backend.intelligence.model.DependencyFinding;
import com.proofix.backend.intelligence.model.DependencyFinding.VersionStatus;
import com.proofix.backend.intelligence.model.DependencyManifestParseResult;
import com.proofix.backend.intelligence.model.EvidenceReference;
import com.proofix.backend.intelligence.model.ParseStatus;
import org.springframework.stereotype.Component;

@Component
public class GradleManifestParser implements DependencyManifestParser {

    private static final Set<String> GRADLE_FILE_NAMES = Set.of(
            "build.gradle",
            "build.gradle.kts");

    private static final Set<String> DEPENDENCY_CONFIGURATIONS = Set.of(
            "implementation",
            "api",
            "compileOnly",
            "runtimeOnly",
            "testImplementation",
            "testRuntimeOnly");

    @Override
    public boolean supports(String repositoryRelativePath) {
        return repositoryRelativePath != null
                && GRADLE_FILE_NAMES.contains(
                        fileName(repositoryRelativePath).toLowerCase(
                                Locale.ROOT));
    }

    @Override
    public DependencyManifestParseResult parse(
            String repositoryRelativePath,
            String persistedContent) {

        if (!supports(repositoryRelativePath)) {
            return result(
                    repositoryRelativePath,
                    ParseStatus.UNSUPPORTED_PATH,
                    List.of("Path is not a supported Gradle build file."));
        }
        if (persistedContent == null) {
            return result(
                    repositoryRelativePath,
                    ParseStatus.NULL_CONTENT,
                    List.of("Persisted file content is unavailable."));
        }
        if (persistedContent.isBlank()) {
            return result(
                    repositoryRelativePath,
                    ParseStatus.EMPTY_CONTENT,
                    List.of("Persisted file content is empty."));
        }

        List<String> warnings = new ArrayList<>();
        List<Token> tokens = tokenize(persistedContent, warnings);
        List<DependencyFinding> dependencies = extractDependencies(
                tokens,
                repositoryRelativePath,
                warnings);

        return new DependencyManifestParseResult(
                repositoryRelativePath,
                ParseStatus.SUCCESS,
                dependencies,
                null,
                null,
                null,
                null,
                null,
                warnings);
    }

    private static List<DependencyFinding> extractDependencies(
            List<Token> tokens,
            String filePath,
            List<String> warnings) {

        List<DependencyFinding> dependencies = new ArrayList<>();
        int dependenciesBlockStart = -1;
        int braceDepth = 0;

        for (int index = 0; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.kind() == TokenKind.SYMBOL) {
                if ("{".equals(token.value())) {
                    braceDepth++;
                } else if ("}".equals(token.value())) {
                    if (dependenciesBlockStart == braceDepth) {
                        dependenciesBlockStart = -1;
                    }
                    braceDepth = Math.max(0, braceDepth - 1);
                }
                continue;
            }

            if (token.kind() != TokenKind.IDENTIFIER) {
                continue;
            }

            if (dependenciesBlockStart < 0
                    && "dependencies".equals(token.value())
                    && isSymbol(tokens, index + 1, "{")) {
                dependenciesBlockStart = braceDepth + 1;
                continue;
            }

            if (dependenciesBlockStart < 0
                    || braceDepth != dependenciesBlockStart
                    || !DEPENDENCY_CONFIGURATIONS.contains(token.value())) {
                continue;
            }

            int argumentIndex = index + 1;
            if (isSymbol(tokens, argumentIndex, "(")) {
                argumentIndex++;
            }

            if (argumentIndex >= tokens.size()) {
                warnings.add("Incomplete Gradle dependency declaration.");
                continue;
            }

            Token argument = tokens.get(argumentIndex);
            if (argument.kind() == TokenKind.STRING) {
                DependencyFinding finding = parseNotation(
                        token.value(),
                        argument.value(),
                        filePath,
                        warnings);
                if (finding != null) {
                    dependencies.add(finding);
                }
                continue;
            }

            warnings.add(unsupportedDeclaration(token.value(), argument));
        }

        return List.copyOf(dependencies);
    }

    private static DependencyFinding parseNotation(
            String configuration,
            String notation,
            String filePath,
            List<String> warnings) {

        String coordinate = notation.trim();
        if (coordinate.isEmpty()) {
            warnings.add("Empty Gradle dependency notation was skipped.");
            return null;
        }

        String type = null;
        int typeSeparator = coordinate.lastIndexOf('@');
        if (typeSeparator > 0) {
            type = coordinate.substring(typeSeparator + 1).trim();
            coordinate = coordinate.substring(0, typeSeparator);
            if (type.isEmpty()) {
                warnings.add("Gradle dependency type was empty.");
                return null;
            }
        }

        String[] parts = coordinate.split(":", -1);
        if (parts.length < 2 || parts.length > 4
                || isBlank(parts[0]) || isBlank(parts[1])) {
            warnings.add(
                    "Unsupported or incomplete Gradle dependency notation: "
                            + notation);
            return null;
        }

        String groupId = parts[0];
        String artifactId = parts[1];
        String version = parts.length >= 3 ? parts[2] : null;
        String classifier = parts.length == 4 ? parts[3] : null;
        if (isBlank(classifier)) {
            classifier = null;
        }

        if (containsExpression(groupId) || containsExpression(artifactId)) {
            warnings.add(
                    "Gradle dependency coordinates contain an unsupported "
                            + "expression: " + notation);
            return null;
        }

        VersionStatus versionStatus = versionStatus(version);
        if (versionStatus == VersionStatus.UNRESOLVED) {
            warnings.add(
                    "Gradle dependency version was not resolved: " + version);
        }

        return new DependencyFinding(
                "gradle",
                groupId,
                artifactId,
                version,
                versionStatus,
                configuration,
                null,
                classifier,
                type,
                new EvidenceReference(filePath, null, null, null, null));
    }

    private static VersionStatus versionStatus(String version) {
        if (isBlank(version)) {
            return VersionStatus.MISSING;
        }
        if (version.matches("\\$\\{[^{}]+\\}")) {
            return VersionStatus.PROPERTY_REFERENCE;
        }
        if (version.contains("$")
                || version.contains("+")
                || version.contains("*")
                || version.contains("[")
                || version.contains("]")
                || version.contains("(")
                || version.contains(")")
                || version.contains(",")
                || version.toLowerCase(Locale.ROOT).startsWith("latest.")) {
            return VersionStatus.UNRESOLVED;
        }
        return VersionStatus.EXPLICIT;
    }

    private static String unsupportedDeclaration(
            String configuration,
            Token argument) {
        if (argument.kind() == TokenKind.IDENTIFIER
                && (argument.value().startsWith("libs")
                        || argument.value().contains("versionCatalog"))) {
            return "Version catalog dependency was not resolved for "
                    + configuration + ".";
        }
        if (argument.kind() == TokenKind.IDENTIFIER
                && "platform".equals(argument.value())) {
            return "Platform or BOM dependency was not reported as a direct "
                    + "dependency.";
        }
        if (argument.kind() == TokenKind.IDENTIFIER
                && "enforcedPlatform".equals(argument.value())) {
            return "Enforced platform dependency was not reported as a direct "
                    + "dependency.";
        }
        if (argument.kind() == TokenKind.IDENTIFIER
                && "project".equals(argument.value())) {
            return "Project dependency was not reported as an external "
                    + "dependency.";
        }
        return "Unsupported or variable-based Gradle dependency declaration "
                + "for " + configuration + ".";
    }

    private static List<Token> tokenize(
            String content,
            List<String> warnings) {

        List<Token> tokens = new ArrayList<>();
        int index = 0;
        while (index < content.length()) {
            char current = content.charAt(index);
            if (Character.isWhitespace(current)) {
                index++;
                continue;
            }
            if (current == '/' && index + 1 < content.length()) {
                char next = content.charAt(index + 1);
                if (next == '/') {
                    index = skipLine(content, index + 2);
                    continue;
                }
                if (next == '*') {
                    int end = content.indexOf("*/", index + 2);
                    if (end < 0) {
                        warnings.add("Unterminated Gradle block comment.");
                        break;
                    }
                    index = end + 2;
                    continue;
                }
            }
            if (current == '#') {
                index = skipLine(content, index + 1);
                continue;
            }
            if (current == '\'' || current == '"') {
                StringToken string = readString(content, index, current);
                tokens.add(new Token(TokenKind.STRING, string.value()));
                index = string.nextIndex();
                if (!string.terminated()) {
                    warnings.add("Unterminated Gradle string literal.");
                }
                continue;
            }
            if (Character.isJavaIdentifierStart(current) || current == '$') {
                int start = index++;
                while (index < content.length()) {
                    char character = content.charAt(index);
                    if (!Character.isJavaIdentifierPart(character)
                            && character != '$'
                            && character != '.'
                            && character != '-') {
                        break;
                    }
                    index++;
                }
                tokens.add(new Token(
                        TokenKind.IDENTIFIER,
                        content.substring(start, index)));
                continue;
            }
            tokens.add(new Token(TokenKind.SYMBOL, String.valueOf(current)));
            index++;
        }
        return List.copyOf(tokens);
    }

    private static StringToken readString(
            String content,
            int start,
            char quote) {

        boolean triple = start + 2 < content.length()
                && content.charAt(start + 1) == quote
                && content.charAt(start + 2) == quote;
        int index = start + (triple ? 3 : 1);
        StringBuilder value = new StringBuilder();
        while (index < content.length()) {
            if (triple
                    && index + 2 < content.length()
                    && content.charAt(index) == quote
                    && content.charAt(index + 1) == quote
                    && content.charAt(index + 2) == quote) {
                return new StringToken(value.toString(), index + 3, true);
            }
            if (!triple && content.charAt(index) == quote) {
                return new StringToken(value.toString(), index + 1, true);
            }
            if (content.charAt(index) == '\\'
                    && index + 1 < content.length()) {
                value.append(content.charAt(index));
                value.append(content.charAt(index + 1));
                index += 2;
            } else {
                value.append(content.charAt(index++));
            }
        }
        return new StringToken(value.toString(), index, false);
    }

    private static int skipLine(String content, int index) {
        int newline = content.indexOf('\n', index);
        return newline < 0 ? content.length() : newline + 1;
    }

    private static boolean isSymbol(
            List<Token> tokens,
            int index,
            String value) {
        return index < tokens.size()
                && tokens.get(index).kind() == TokenKind.SYMBOL
                && value.equals(tokens.get(index).value());
    }

    private static boolean containsExpression(String value) {
        return value.contains("$") || value.contains("{") || value.contains("}");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String fileName(String path) {
        String normalized = path.replace('\\', '/');
        return normalized.substring(normalized.lastIndexOf('/') + 1);
    }

    private static DependencyManifestParseResult result(
            String filePath,
            ParseStatus status,
            List<String> warnings) {
        return new DependencyManifestParseResult(
                filePath,
                status,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                warnings);
    }

    private enum TokenKind {
        IDENTIFIER,
        STRING,
        SYMBOL
    }

    private record Token(TokenKind kind, String value) {
    }

    private record StringToken(
            String value,
            int nextIndex,
            boolean terminated) {
    }
}
