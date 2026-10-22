package com.dastakhat.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The canonical JSON has to be identical to the representation the original brief used
 * (Python {@code json.dumps(sort_keys=True, separators=(",", ":"))}) so that an audit chain
 * can be verified by either implementation.
 */
class CanonicalJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void objectKeysAreSortedAndWhitespaceIsRemoved() {
        ObjectNode node = mapper.createObjectNode();
        node.put("zeta", "z");
        node.put("alpha", 1);
        node.put("mid", true);
        assertEquals("{\"alpha\":1,\"mid\":true,\"zeta\":\"z\"}", CanonicalJson.canonicalize(node));
    }

    @Test
    void nestedObjectsAreSortedAsWell() {
        ObjectNode node = mapper.createObjectNode();
        ObjectNode inner = mapper.createObjectNode();
        inner.put("b", "2");
        inner.put("a", "1");
        node.set("outer", inner);
        assertEquals("{\"outer\":{\"a\":\"1\",\"b\":\"2\"}}", CanonicalJson.canonicalize(node));
    }

    @Test
    void theOrderOfTheInputDoesNotMatter() {
        ObjectNode first = mapper.createObjectNode();
        first.put("b", "2");
        first.put("a", "1");
        ObjectNode second = mapper.createObjectNode();
        second.put("a", "1");
        second.put("b", "2");
        assertEquals(CanonicalJson.canonicalize(first), CanonicalJson.canonicalize(second));
    }

    @Test
    void differentContentProducesADifferentRepresentation() {
        ObjectNode first = mapper.createObjectNode();
        first.put("a", "1");
        ObjectNode second = mapper.createObjectNode();
        second.put("a", "2");
        assertNotEquals(CanonicalJson.canonicalize(first), CanonicalJson.canonicalize(second));
    }

    @Test
    void stringsAreEscaped() {
        ObjectNode node = mapper.createObjectNode();
        node.put("text", "line1\nline2 \"quoted\"");
        assertEquals("{\"text\":\"line1\\nline2 \\\"quoted\\\"\"}", CanonicalJson.canonicalize(node));
    }
}
