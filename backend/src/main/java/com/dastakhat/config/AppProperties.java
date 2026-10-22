package com.dastakhat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Typed application settings. Every value can be overridden with an environment variable,
 * e.g. {@code DASTAKHAT_STORAGE_DIR}. Nothing secret is ever stored in the repository.
 */
@ConfigurationProperties(prefix = "dastakhat")
public class AppProperties {

    /** Root directory for uploaded and generated documents. Never inside a web root. */
    private String storageDir = "./storage";

    /** Allowed CORS origins for the SPA. */
    private List<String> corsOrigins = new ArrayList<>(List.of("http://localhost:5173", "http://127.0.0.1:5173"));

    /** Public base URL of the frontend, used to build the signing links inside e-mails. */
    private String publicBaseUrl = "http://localhost:5173";

    /** Default lifetime of a signing link. */
    private int defaultLinkExpiryDays = 7;

    /** Maximum accepted upload size. */
    private long maxUploadBytes = 10L * 1024 * 1024;

    /** Maximum accepted page count. */
    private int maxPages = 50;

    /** Maximum size of a signature image. */
    private long maxSignatureBytes = 200L * 1024;

    /** Session idle timeout. */
    private Duration sessionTimeout = Duration.ofMinutes(120);

    /** Mark the session cookie as {@code Secure}. Only enable behind HTTPS. */
    private boolean cookieSecure = false;

    /** Path to the PKCS#12 keystore holding the platform seal key (development only). */
    private String sealKeystore = "../devpki/out/platform-seal.p12";

    /** Keystore password of the platform seal keystore. */
    private String sealKeystorePassword = "changeit";

    /** PEM file with the platform seal certificate. */
    private String sealCertificate = "../devpki/out/platform-seal-cert.pem";

    /** PEM file(s) with the trusted roots used to validate signer certificates. */
    private List<String> trustRoots = new ArrayList<>(List.of("../devpki/out/test-root-ca.pem"));

    /** Base URL of the MOCK eSign provider. */
    private String mockEspBaseUrl = "http://localhost:9100";

    /** Absolute URL the MOCK ESP posts the signed response back to. */
    private String esignCallbackUrl = "http://localhost:8080/api/v1/esign/callback";

    /** Base URL of the local DSC signing agent. */
    private String dscAgentBaseUrl = "https://127.0.0.1:51515";

    /** Fixed test OTP displayed by the MOCK ESP. Never a real OTP. */
    private String mockEspTestOtp = "123456";

    /** The sender address of the platform e-mails. */
    private String mailFromAddress = "no-reply@dastakhat.local";

    /** The display name of the platform e-mails. */
    private String mailFromName = "Dastakhat";

    /** Settings of the MOCK eSign integration (Stage 3 - simulation only). */
    private Esign esign = new Esign();

    /** Configuration of the MOCK eSign provider integration. */
    public static class Esign {
        /** PKCS#12 keystore with the ASP key that signs the MOCK eSign request. */
        private String aspKeystore = "../devpki/out/mock-asp.p12";
        private String aspKeystorePassword = "changeit";
        /** PEM with the certificate of the MOCK ESP - the responses are verified against it. */
        private String espCertificate = "../devpki/out/mock-esp-cert.pem";
        /** PEM with the MOCK eSign CA - the one time user certificates are checked against it. */
        private String mockCaCertificate = "../devpki/out/mock-ca.pem";
        /** The identity of the application service provider (a test value). */
        private String aspId = "MOCK-ASP-001";

        public String getAspKeystore() { return aspKeystore; }
        public void setAspKeystore(String aspKeystore) { this.aspKeystore = aspKeystore; }
        public String getAspKeystorePassword() { return aspKeystorePassword; }
        public void setAspKeystorePassword(String p) { this.aspKeystorePassword = p; }
        public String getEspCertificate() { return espCertificate; }
        public void setEspCertificate(String espCertificate) { this.espCertificate = espCertificate; }
        public String getMockCaCertificate() { return mockCaCertificate; }
        public void setMockCaCertificate(String v) { this.mockCaCertificate = v; }
        public String getAspId() { return aspId; }
        public void setAspId(String aspId) { this.aspId = aspId; }
    }

    /** Development seed account (created on first start). */
    private Seed seed = new Seed();

    public static class Seed {
        private String email = "sender@example.test";
        private String name = "Dastakhat Sender";
        private String password = "ChangeMe!123";
        private boolean enabled = true;

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public String getStorageDir() { return storageDir; }
    public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
    public List<String> getCorsOrigins() { return corsOrigins; }
    public void setCorsOrigins(List<String> corsOrigins) { this.corsOrigins = corsOrigins; }
    public String getPublicBaseUrl() { return publicBaseUrl; }
    public void setPublicBaseUrl(String publicBaseUrl) { this.publicBaseUrl = publicBaseUrl; }
    public int getDefaultLinkExpiryDays() { return defaultLinkExpiryDays; }
    public void setDefaultLinkExpiryDays(int defaultLinkExpiryDays) { this.defaultLinkExpiryDays = defaultLinkExpiryDays; }
    public long getMaxUploadBytes() { return maxUploadBytes; }
    public void setMaxUploadBytes(long maxUploadBytes) { this.maxUploadBytes = maxUploadBytes; }
    public int getMaxPages() { return maxPages; }
    public void setMaxPages(int maxPages) { this.maxPages = maxPages; }
    public long getMaxSignatureBytes() { return maxSignatureBytes; }
    public void setMaxSignatureBytes(long maxSignatureBytes) { this.maxSignatureBytes = maxSignatureBytes; }
    public Duration getSessionTimeout() { return sessionTimeout; }
    public void setSessionTimeout(Duration sessionTimeout) { this.sessionTimeout = sessionTimeout; }
    public boolean isCookieSecure() { return cookieSecure; }
    public void setCookieSecure(boolean cookieSecure) { this.cookieSecure = cookieSecure; }
    public String getSealKeystore() { return sealKeystore; }
    public void setSealKeystore(String sealKeystore) { this.sealKeystore = sealKeystore; }
    public String getSealKeystorePassword() { return sealKeystorePassword; }
    public void setSealKeystorePassword(String sealKeystorePassword) { this.sealKeystorePassword = sealKeystorePassword; }
    public String getSealCertificate() { return sealCertificate; }
    public void setSealCertificate(String sealCertificate) { this.sealCertificate = sealCertificate; }
    public List<String> getTrustRoots() { return trustRoots; }
    public void setTrustRoots(List<String> trustRoots) { this.trustRoots = trustRoots; }
    public String getMockEspBaseUrl() { return mockEspBaseUrl; }
    public void setMockEspBaseUrl(String mockEspBaseUrl) { this.mockEspBaseUrl = mockEspBaseUrl; }
    public String getEsignCallbackUrl() { return esignCallbackUrl; }
    public void setEsignCallbackUrl(String esignCallbackUrl) { this.esignCallbackUrl = esignCallbackUrl; }
    public String getDscAgentBaseUrl() { return dscAgentBaseUrl; }
    public void setDscAgentBaseUrl(String dscAgentBaseUrl) { this.dscAgentBaseUrl = dscAgentBaseUrl; }
    public String getMockEspTestOtp() { return mockEspTestOtp; }
    public void setMockEspTestOtp(String mockEspTestOtp) { this.mockEspTestOtp = mockEspTestOtp; }
    public String getMailFromAddress() { return mailFromAddress; }
    public void setMailFromAddress(String mailFromAddress) { this.mailFromAddress = mailFromAddress; }
    public String getMailFromName() { return mailFromName; }
    public void setMailFromName(String mailFromName) { this.mailFromName = mailFromName; }
    public Seed getSeed() { return seed; }
    public void setSeed(Seed seed) { this.seed = seed; }
    public Esign getEsign() { return esign; }
    public void setEsign(Esign esign) { this.esign = esign; }
}
