package com.dastakhat.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Deterministic JSON rendering used for the audit hash chain.
 *
 * <p>The representation must be stable across JVMs and code changes, therefore object keys are
 * sorted, whitespace is removed and numbers are rendered in their canonical integral form.
 * The output mirrors Python's {@code json.dumps(sort_keys=True, separators=(",", ":"))} which is
 * what the original brief used, so a chain written by either implementation verifies.</p>
 */
public final class CanonicalJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CanonicalJson() {
    }

    /** Serialises any Jackson tree into the canonical form. */
    public static String canonicalize(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        write(node, sb);
        return sb.toString();
    }

    /** Serialises a POJO into the canonical form. */
    public static String canonicalize(Object value) {
        try {
            return canonicalize(MAPPER.readTree(MAPPER.writeValueAsString(value)));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Value cannot be canonicalised", e);
        }
    }

    private static void write(JsonNode node, StringBuilder sb) {
        if (node == null || node.isNull()) {
            sb.append("null");
            return;
        }
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            List<String> names = new ArrayList<>();
            obj.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            sb.append('{');
            boolean first = true;
            for (String name : names) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(name, sb);
                sb.append(':');
                write(obj.get(name), sb);
            }
            sb.append('}');
            return;
        }
        if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            sb.append('[');
            boolean first = true;
            for (JsonNode child : arr) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(child, sb);
            }
            sb.append(']');
            return;
        }
        if (node.isTextual()) {
            writeString(node.textValue(), sb);
            return;
        }
        if (node.isBoolean()) {
            sb.append(node.booleanValue() ? "true" : "false");
            return;
        }
        if (node.isIntegralNumber()) {
            sb.append(node.bigIntegerValue().toString());
            return;
        }
        if (node.isNumber()) {
            // Canonicalise floating point through BigDecimal to avoid platform differences.
            sb.append(node.decimalValue().stripTrailingZeros().toPlainString());
            return;
        }
        sb.append("null");
    }

    private static void writeString(String value, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
