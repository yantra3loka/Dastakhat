package com.dastakhat.documents;

/** The kinds of document that are stored for an envelope. */
public enum DocumentKind {
    /** The file the sender uploaded. */
    ORIGINAL("original"),
    /** The stamped / progressively signed working copy. */
    WORKING("working"),
    /** The flattened, certified and sealed final document. */
    FINAL("final");

    private final String value;

    DocumentKind(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static DocumentKind fromValue(String value) {
        for (DocumentKind kind : values()) {
            if (kind.value.equalsIgnoreCase(value)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown document kind: " + value);
    }
}
