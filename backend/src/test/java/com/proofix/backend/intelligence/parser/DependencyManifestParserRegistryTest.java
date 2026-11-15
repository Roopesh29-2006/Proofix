package com.proofix.backend.intelligence.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.proofix.backend.intelligence.model.DependencyFinding;
import com.proofix.backend.intelligence.model.DependencyManifestParseResult;
import com.proofix.backend.intelligence.model.EvidenceReference;
import com.proofix.backend.intelligence.model.ParseStatus;
import org.junit.jupiter.api.Test;

class DependencyManifestParserRegistryTest {

    private static final String PATH = "modules/api/pom.xml";
    private static final String CONTENT = "<project/>";

    @Test
    void selectsTheOnlySupportingParser() {
        DependencyManifestParser parser = mockParser(true);
        DependencyManifestParseResult expected = result(ParseStatus.SUCCESS);
        when(parser.parse(PATH, CONTENT)).thenReturn(expected);
        DependencyManifestParserRegistry registry =
                new DependencyManifestParserRegistry(List.of(parser));

        assertSame(expected, registry.parse(PATH, CONTENT));

        verify(parser).supports(PATH);
        verify(parser).parse(PATH, CONTENT);
    }

    @Test
    void passesOriginalPathAndContentUnchanged() {
        DependencyManifestParser parser = mockParser(true);
        DependencyManifestParseResult expected = result(ParseStatus.SUCCESS);
        when(parser.parse(PATH, CONTENT)).thenReturn(expected);
        DependencyManifestParserRegistry registry =
                new DependencyManifestParserRegistry(List.of(parser));

        registry.parse(PATH, CONTENT);

        verify(parser).supports(PATH);
        verify(parser).parse(PATH, CONTENT);
    }

    @Test
    void returnsUnsupportedResultWhenNoParserSupportsPath() {
        DependencyManifestParser parser = mockParser(false);
        DependencyManifestParserRegistry registry =
                new DependencyManifestParserRegistry(List.of(parser));

        DependencyManifestParseResult result = registry.parse(PATH, CONTENT);

        assertEquals(ParseStatus.UNSUPPORTED_PATH, result.status());
        assertEquals(PATH, result.filePath());
        assertTrue(result.dependencies().isEmpty());
        assertTrue(result.warnings().stream().anyMatch(
                warning -> !warning.isBlank()));
        verify(parser).supports(PATH);
        verify(parser, never()).parse(PATH, CONTENT);
    }

    @Test
    void preservesUnsupportedPathWithEmptyParserList() {
        DependencyManifestParserRegistry registry =
                new DependencyManifestParserRegistry(List.of());

        DependencyManifestParseResult result = registry.parse(PATH, CONTENT);

        assertEquals(ParseStatus.UNSUPPORTED_PATH, result.status());
        assertEquals(PATH, result.filePath());
        assertTrue(result.dependencies().isEmpty());
    }

    @Test
    void delegatesNullContentAndPreservesNullContentResult() {
        DependencyManifestParser parser = mockParser(true);
        DependencyManifestParseResult expected =
                result(ParseStatus.NULL_CONTENT);
        when(parser.parse(PATH, null)).thenReturn(expected);
        DependencyManifestParserRegistry registry =
                new DependencyManifestParserRegistry(List.of(parser));

        assertSame(expected, registry.parse(PATH, null));

        verify(parser).parse(PATH, null);
    }

    @Test
    void rejectsAmbiguousParserSelection() {
        DependencyManifestParser first = mockParser(true);
        DependencyManifestParser second = mockParser(true);
        DependencyManifestParserRegistry registry =
                new DependencyManifestParserRegistry(List.of(first, second));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> registry.parse(PATH, CONTENT));

        assertTrue(exception.getMessage().contains(PATH));
        verify(first).supports(PATH);
        verify(second).supports(PATH);
    }

    @Test
    void doesNotInvokeNonSupportingParser() {
        DependencyManifestParser supporting = mockParser(true);
        DependencyManifestParser nonSupporting = mockParser(false);
        DependencyManifestParseResult expected = result(ParseStatus.SUCCESS);
        when(supporting.parse(PATH, CONTENT)).thenReturn(expected);
        DependencyManifestParserRegistry registry =
                new DependencyManifestParserRegistry(
                        List.of(supporting, nonSupporting));

        assertSame(expected, registry.parse(PATH, CONTENT));

        verify(nonSupporting).supports(PATH);
        verify(nonSupporting, never()).parse(PATH, CONTENT);
    }

    @Test
    void propagatesParserRuntimeExceptions() {
        DependencyManifestParser parser = mockParser(true);
        IllegalStateException failure = new IllegalStateException("failure");
        doThrow(failure).when(parser).parse(PATH, CONTENT);
        DependencyManifestParserRegistry registry =
                new DependencyManifestParserRegistry(List.of(parser));

        assertSame(
                failure,
                assertThrows(
                        IllegalStateException.class,
                        () -> registry.parse(PATH, CONTENT)));
    }

    @Test
    void preservesParserFindingsAndWarnings() {
        DependencyManifestParser parser = mockParser(true);
        DependencyFinding finding = new DependencyFinding(
                "maven",
                "com.example",
                "library",
                "1.0.0",
                DependencyFinding.VersionStatus.EXPLICIT,
                null,
                false,
                null,
                null,
                new EvidenceReference(PATH, null, null, null, null));
        DependencyManifestParseResult expected =
                new DependencyManifestParseResult(
                        PATH,
                        ParseStatus.SUCCESS,
                        List.of(finding),
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of("parser warning"));
        when(parser.parse(PATH, CONTENT)).thenReturn(expected);
        DependencyManifestParserRegistry registry =
                new DependencyManifestParserRegistry(List.of(parser));

        DependencyManifestParseResult actual = registry.parse(PATH, CONTENT);

        assertSame(expected, actual);
        assertEquals(List.of(finding), actual.dependencies());
        assertEquals(List.of("parser warning"), actual.warnings());
    }

    private static DependencyManifestParser mockParser(boolean supports) {
        DependencyManifestParser parser = mock(
                DependencyManifestParser.class);
        when(parser.supports(PATH)).thenReturn(supports);
        return parser;
    }

    private static DependencyManifestParseResult result(ParseStatus status) {
        return new DependencyManifestParseResult(
                PATH,
                status,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                List.of());
    }

}
