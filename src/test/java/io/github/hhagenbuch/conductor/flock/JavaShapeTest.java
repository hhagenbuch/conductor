package io.github.hhagenbuch.conductor.flock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The Java-DTO → JSON-Schema shape reader. Records and classes, type mapping,
/// and `Optional` unwrapping (which decides required-ness, the load-bearing
/// signal for "added a required field" = BREAKING).
class JavaShapeTest {

    @Test
    void recordComponentsBecomePropertiesWithRequiredness() {
        var schema = JavaShape.schemaOf(
                "public record OrderRequest(String sku, int qty, Optional<String> promoCode) {}");
        var props = schema.path("properties");
        assertEquals("string", props.path("sku").path("type").asText());
        assertEquals("number", props.path("qty").path("type").asText());
        assertEquals("string", props.path("promoCode").path("type").asText());
        var required = schema.path("required").toString();
        assertTrue(required.contains("sku") && required.contains("qty"), required);
        assertFalse(required.contains("promoCode"), "Optional<> components are not required");
    }

    @Test
    void classFieldsAreParsedWithJsonTypes() {
        var schema = JavaShape.schemaOf("""
                public class Money {
                    private String currency;
                    private java.math.BigDecimal amount;
                    private boolean refundable;
                    private java.util.List<String> tags;
                }
                """);
        var props = schema.path("properties");
        assertEquals("string", props.path("currency").path("type").asText());
        assertEquals("number", props.path("amount").path("type").asText());
        assertEquals("boolean", props.path("refundable").path("type").asText());
        assertEquals("array", props.path("tags").path("type").asText());
    }

    @Test
    void genericsWithNestedCommasDoNotSplitComponents() {
        var schema = JavaShape.schemaOf(
                "record Reg(java.util.Map<String,Integer> byName, String id) {}");
        var props = schema.path("properties");
        assertEquals(2, props.size(), "Map<String,Integer> must count as one component: " + props);
        assertEquals("object", props.path("byName").path("type").asText());
        assertEquals("string", props.path("id").path("type").asText());
    }

    @Test
    void unparseableSourceYieldsEmptyShapeNotGarbage() {
        var schema = JavaShape.schemaOf("// just a comment\npackage x;\n");
        assertTrue(schema.path("properties").isEmpty());
        assertTrue(schema.path("required").isEmpty());
    }
}
