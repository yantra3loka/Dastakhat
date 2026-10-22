package com.dastakhat.envelopes;

/** Routing mode of an envelope. */
public enum RoutingMode {
    SEQUENTIAL("sequential"),
    PARALLEL("parallel");

    private final String value;

    RoutingMode(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static RoutingMode fromValue(String value) {
        for (RoutingMode mode : values()) {
            if (mode.value.equalsIgnoreCase(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown routing mode: " + value);
    }
}
