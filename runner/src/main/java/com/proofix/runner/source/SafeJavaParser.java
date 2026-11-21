package com.proofix.runner.source;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Problem;
import com.github.javaparser.Range;
import com.github.javaparser.TokenRange;
import com.github.javaparser.ast.CompilationUnit;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * [SEC] Parses Java source text with JavaParser at language level JAVA_21. The source is only
 * parsed, never compiled, resolved or run. Parser problems and exceptions can quote source text,
 * so their messages are never read: a failure carries only the line of the first problem (when
 * the parser knows it). Callers turn it into a warning with fixed text.
 */
public final class SafeJavaParser {

    /** The fixed warning message for a source that could not be parsed. */
    public static final String UNPARSEABLE_MESSAGE = "Java source could not be parsed; endpoints in it are not listed";

    /** Either a compilation unit or a failure with an optional 1-based line. */
    public sealed interface Outcome permits Parsed, Failed {}

    public record Parsed(CompilationUnit unit) implements Outcome {}

    public record Failed(Integer line) implements Outcome {}

    private final JavaParser parser;

    public SafeJavaParser() {
        ParserConfiguration cfg = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setAttributeComments(false)
                .setLexicalPreservationEnabled(false);
        this.parser = new JavaParser(cfg);
    }

    public Outcome parse(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '﻿') text = text.substring(1);
        try {
            ParseResult<CompilationUnit> r = parser.parse(text);
            if (r.isSuccessful() && r.getResult().isPresent()) return new Parsed(r.getResult().get());
            return new Failed(firstLine(r));
        } catch (RuntimeException | StackOverflowError e) {
            // Deliberately ignore e (its message can quote the source).
            return new Failed(null);
        }
    }

    private static Integer firstLine(ParseResult<CompilationUnit> r) {
        for (Problem p : r.getProblems()) {
            Optional<Range> range = p.getLocation().flatMap(TokenRange::toRange);
            if (range.isPresent() && range.get().begin.line >= 1) return range.get().begin.line;
        }
        return null;
    }
}
