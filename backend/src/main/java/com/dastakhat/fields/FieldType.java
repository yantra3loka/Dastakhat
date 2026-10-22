package com.dastakhat.fields;

/** The kind of a placed field. */
public enum FieldType {
    SIGNATURE("signature"),
    INITIALS("initials"),
    DATE("date"),
    TEXT("text"),
    NAME("name");

    private final String value;

    FieldType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static FieldType fromValue(String value) {
        for (FieldType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown field type: " + value);
    }
}
