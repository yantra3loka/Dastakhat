package com.dastakhat.recipients;

/** State of a recipient inside the workflow. */
public enum RecipientStatus {
    PENDING("pending"),
    NOTIFIED("notified"),
    VIEWED("viewed"),
    SIGNED("signed"),
    DECLINED("declined");

    private final String value;

    RecipientStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static RecipientStatus fromValue(String value) {
        for (RecipientStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown recipient status: " + value);
    }
}
