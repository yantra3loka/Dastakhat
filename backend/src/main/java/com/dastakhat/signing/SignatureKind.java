package com.dastakhat.signing;

/** The kind of signature that was captured. */
public enum SignatureKind {
    /** Free hand drawing on a canvas. */
    DRAWN("drawn"),
    /** A typed name rendered as an image. NOT a cryptographic signature. */
    TYPED("typed"),
    /** Produced with a DSC through the local PKCS#11 agent. */
    DSC("dsc"),
    /** Produced by the MOCK eSign provider. */
    ESIGN_MOCK("esign_mock");

    private final String value;

    SignatureKind(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static SignatureKind fromValue(String value) {
        for (SignatureKind kind : values()) {
            if (kind.value.equalsIgnoreCase(value)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown signature kind: " + value);
    }

    /** True when the signature carries a real cryptographic signature. */
    public boolean isCryptographic() {
        return this == DSC || this == ESIGN_MOCK;
    }
}
