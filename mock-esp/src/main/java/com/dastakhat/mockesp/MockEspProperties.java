package com.dastakhat.mockesp;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** The configuration of the MOCK eSign provider. */
@ConfigurationProperties(prefix = "mock-esp")
public class MockEspProperties {

    /** The keystore with the MOCK ESP key and certificate. */
    private String keystore = "../devpki/out/mock-esp.p12";

    private String keystorePassword = "changeit";

    /**
     * The keystore with the MOCK CA key and certificate.
     *
     * <p>The provider signs the one time certificate of a "user" with this key, exactly like a
     * real eSign service provider would. It is a TEST key of the MOCK CA.</p>
     */
    private String caKeystore = "../devpki/out/mock-ca.p12";

    private String caKeystorePassword = "changeit";

    /** The PEM file with the MOCK CA certificate. */
    private String caCertificate = "../devpki/out/mock-ca.pem";

    /** The fixed test OTP that is shown on the OTP page. Never a real OTP. */
    private String testOtp = "123456";

    /** The origins that may call the provider. */
    private java.util.List<String> allowedOrigins =
            new java.util.ArrayList<>(java.util.List.of("http://localhost:5173", "http://127.0.0.1:5173"));

    /** How long a consent page stays valid. */
    private Duration requestExpiry = Duration.ofMinutes(10);

    public String getKeystore() { return keystore; }
    public void setKeystore(String keystore) { this.keystore = keystore; }
    public String getKeystorePassword() { return keystorePassword; }
    public void setKeystorePassword(String keystorePassword) { this.keystorePassword = keystorePassword; }
    public String getCaKeystore() { return caKeystore; }
    public void setCaKeystore(String caKeystore) { this.caKeystore = caKeystore; }
    public String getCaKeystorePassword() { return caKeystorePassword; }
    public void setCaKeystorePassword(String p) { this.caKeystorePassword = p; }
    public String getCaCertificate() { return caCertificate; }
    public void setCaCertificate(String caCertificate) { this.caCertificate = caCertificate; }
    public String getTestOtp() { return testOtp; }
    public void setTestOtp(String testOtp) { this.testOtp = testOtp; }
    public java.util.List<String> getAllowedOrigins() { return allowedOrigins; }
    public void setAllowedOrigins(java.util.List<String> allowedOrigins) { this.allowedOrigins = allowedOrigins; }
    public Duration getRequestExpiry() { return requestExpiry; }
    public void setRequestExpiry(Duration requestExpiry) { this.requestExpiry = requestExpiry; }
}
