package com.dastakhat.envelopes;

/** Lifecycle of an envelope. Only {@link #DRAFT} envelopes are editable. */
public enum EnvelopeStatus {
    DRAFT("draft"),
    SENT("sent"),
    COMPLETED("completed"),
    DECLINED("declined"),
    VOIDED("voided"),
    EXPIRED("expired");

    private final String value;

    EnvelopeStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static EnvelopeStatus fromValue(String value) {
        for (EnvelopeStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown envelope status: " + value);
    }
}
