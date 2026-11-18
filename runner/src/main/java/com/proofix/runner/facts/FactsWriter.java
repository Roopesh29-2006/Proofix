package com.proofix.runner.facts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.proofix.runner.facts.model.Facts;

import java.nio.charset.StandardCharsets;

/**
 * Serializes facts.json deterministically: fixed property order, LF line endings on every
 * platform (Jackson defaults to the system separator), UTF-8, trailing newline.
 */
public final class FactsWriter {
    private FactsWriter() {}

    private static final ObjectWriter WRITER;

    static {
        DefaultIndenter lf = new DefaultIndenter("  ", "\n");
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                .withSeparators(Separators.createDefaultInstance()
                        .withObjectFieldValueSpacing(Separators.Spacing.AFTER));
        printer.indentObjectsWith(lf);
        printer.indentArraysWith(lf);
        WRITER = new ObjectMapper()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .writer(printer);
    }

    public static byte[] toBytes(Facts facts) {
        try {
            String json = WRITER.writeValueAsString(facts) + "\n";
            return json.getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("facts serialization failed", e);
        }
    }
}
