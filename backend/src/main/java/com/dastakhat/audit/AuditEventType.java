package com.dastakhat.audit;

/** The kind of an audit event. */
public enum AuditEventType {
    ENVELOPE_CREATED("envelope_created"),
    DOCUMENT_UPLOADED("document_uploaded"),
    RECIPIENTS_UPDATED("recipients_updated"),
    FIELDS_UPDATED("fields_updated"),
    ENVELOPE_SENT("envelope_sent"),
    DOCUMENT_VIEWED("document_viewed"),
    CONSENT_RECORDED("consent_recorded"),
    SIGNED("signed"),
    DECLINED("declined"),
    DSC_SIGNED("dsc_signed"),
    ESIGN_SIGNED("esign_signed"),
    SEALED("sealed"),
    DOWNLOADED("downloaded"),
    VOIDED("voided");

    private final String value;

    AuditEventType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** Parses the value of an audit event type. */
    public static AuditEventType fromValue(String value) {
        for (AuditEventType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown audit event type: " + value);
    }
}
