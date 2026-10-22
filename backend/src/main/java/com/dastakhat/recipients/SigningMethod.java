package com.dastakhat.recipients;

/** How a recipient signs. */
public enum SigningMethod {
    /** Drawn or typed image. NOT a cryptographic digital signature. */
    ELECTRONIC("electronic"),
    /** Digital Signature Certificate through the local PKCS#11 agent. */
    DSC("dsc"),
    /** Aadhaar eSign - MOCK ONLY, never contacts UIDAI or a real provider. */
    ESIGN_MOCK("esign_mock");

    private final String value;

    SigningMethod(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static SigningMethod fromValue(String value) {
        for (SigningMethod method : values()) {
            if (method.value.equalsIgnoreCase(value)) {
                return method;
            }
        }
        throw new IllegalArgumentException("Unknown signing method: " + value);
    }

    /** True for the methods that produce a real cryptographic signature. */
    public boolean isCryptographic() {
        return this == DSC || this == ESIGN_MOCK;
    }
}
