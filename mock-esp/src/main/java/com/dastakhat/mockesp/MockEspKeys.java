package com.dastakhat.mockesp;

import com.dastakhat.pki.CmsBuilder;
import com.dastakhat.pki.XmlSigner;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * The key material of the MOCK eSign provider.
 *
 * <p>Everything is test data of the MOCK CA. The one time certificates that the mock issues
 * for a "user" are created here and are valid for nobody.</p>
 */
@Component
public class MockEspKeys {

    private static final Logger log = LoggerFactory.getLogger(MockEspKeys.class);

    private static final BouncyCastleProvider BC = new BouncyCastleProvider();

    private volatile KeyStore keystore;
    private volatile X509Certificate espCertificate;
    private volatile PrivateKey espPrivateKey;
    private volatile X509Certificate caCertificate;
    private volatile PrivateKey caPrivateKey;

    /** Loads the keystore of the mock. */
    public synchronized void load() {
        if (keystore != null) {
            return;
        }
        try {
            Path path = Path.of(properties.getKeystore());
            if (!Files.exists(path)) {
                log.warn("The MOCK keystore {} does not exist. Run the devpki tool first.", path);
                return;
            }
            KeyStore store = loadStore(path, properties.getKeystorePassword());
            this.keystore = store;
            // The ESP key. There is no fallback alias: signing with the wrong key would produce
            // certificates that the backend (rightfully) rejects.
            String espAlias = requireAlias(store, "mock-esp", path);
            this.espPrivateKey = (PrivateKey) store.getKey(espAlias, properties.getKeystorePassword().toCharArray());
            this.espCertificate = (X509Certificate) store.getCertificate(espAlias);

            // The MOCK CA key. The one time certificate of a "user" is signed with it, so the
            // backend can verify that certificate against the MOCK CA.
            if (store.containsAlias("mock-ca")) {
                loadCa(store, properties.getKeystorePassword(), path);
            } else {
                Path caPath = Path.of(properties.getCaKeystore());
                if (!Files.exists(caPath)) {
                    log.error("The MOCK CA keystore {} does not exist. The provider cannot issue "
                            + "one time certificates. Run the devpki tool first.", caPath);
                    this.keystore = null;
                    return;
                }
                loadCa(loadStore(caPath, properties.getCaKeystorePassword()),
                        properties.getCaKeystorePassword(), caPath);
            }
            log.info("MOCK eSign provider ready (ESP certificate {}, MOCK CA certificate {})",
                    espCertificate.getSubjectX500Principal(), caCertificate.getSubjectX500Principal());
        } catch (Exception e) {
            this.keystore = null;
            log.error("Cannot load the MOCK keystore", e);
        }
    }

    private KeyStore loadStore(Path path, String password) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(path)) {
            store.load(in, password.toCharArray());
        }
        return store;
    }

    private void loadCa(KeyStore store, String password, Path path) throws Exception {
        String caAlias = requireAlias(store, "mock-ca", path);
        this.caPrivateKey = (PrivateKey) store.getKey(caAlias, password.toCharArray());
        this.caCertificate = (X509Certificate) store.getCertificate(caAlias);
        if (this.caPrivateKey == null || this.caCertificate == null) {
            throw new IllegalStateException("The MOCK CA keystore contains no usable key pair.");
        }
    }

    private String requireAlias(KeyStore store, String wanted, Path path) throws java.security.KeyStoreException {
        if (!store.containsAlias(wanted)) {
            throw new IllegalStateException("The keystore " + path.toAbsolutePath()
                    + " contains no entry '" + wanted + "'. Refusing to sign with another key.");
        }
        return wanted;
    }

    private final MockEspProperties properties;

    public MockEspKeys(MockEspProperties properties) {
        this.properties = properties;
    }

    public X509Certificate getEspCertificate() {
        load();
        return espCertificate;
    }

    public PrivateKey getEspPrivateKey() {
        load();
        return espPrivateKey;
    }

    public X509Certificate getCaCertificate() {
        load();
        return caCertificate;
    }

    public PrivateKey getCaPrivateKey() {
        load();
        return caPrivateKey;
    }

    /** True when the key material is available. */
    public boolean isReady() {
        load();
        return espCertificate != null && espPrivateKey != null
                && caCertificate != null && caPrivateKey != null;
    }

    /**
     * Creates a one time certificate for the "user" and signs the document hash with it.
     *
     * @param documentDigest the SHA-256 digest of the bytes that are covered by the signature
     * @return the CMS signature (DER) and the one time certificate
     */
    public OneTimeSignature signDocumentHash(byte[] documentDigest) throws Exception {
        load();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", BC);
        generator.initialize(2048, new SecureRandom());
        KeyPair userKeys = generator.generateKeyPair();

        Instant now = Instant.now();
        X500Name subject = new X500Name("CN=MOCK eSign User (TEST), O=Dastakhat MOCK, C=IN");
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                caCertificate, BigInteger.valueOf(System.currentTimeMillis()),
                Date.from(now), Date.from(now.plusSeconds(600)), subject, userKeys.getPublic());
        builder.addExtension(org.bouncycastle.asn1.x509.Extension.basicConstraints, true,
                new org.bouncycastle.asn1.x509.BasicConstraints(false));
        builder.addExtension(org.bouncycastle.asn1.x509.Extension.keyUsage, true,
                new org.bouncycastle.asn1.x509.KeyUsage(
                        org.bouncycastle.asn1.x509.KeyUsage.digitalSignature
                                | org.bouncycastle.asn1.x509.KeyUsage.nonRepudiation));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").setProvider(BC)
                .build(caPrivateKey);
        X509Certificate userCertificate = new JcaX509CertificateConverter().setProvider(BC)
                .getCertificate(builder.build(signer));

        CmsBuilder.Prepared prepared = CmsBuilder.prepare(documentDigest, userCertificate,
                List.of(caCertificate), now);
        java.security.Signature signerApi = java.security.Signature.getInstance("SHA256withRSA", BC);
        signerApi.initSign(userKeys.getPrivate());
        signerApi.update(prepared.signedAttributesDer());
        byte[] rawSignature = signerApi.sign();
        byte[] cms = CmsBuilder.assemble(prepared.signedAttributesDer(), rawSignature, userCertificate,
                List.of(caCertificate));
        return new OneTimeSignature(cms, userCertificate);
    }

    /** The CMS and the one time certificate of a mocked signature. */
    public record OneTimeSignature(byte[] cms, X509Certificate certificate) { }

    /** Signs the response XML with the MOCK ESP key. */
    public void signResponse(org.w3c.dom.Element element) throws Exception {
        load();
        XmlSigner.sign(element, espPrivateKey, espCertificate);
    }

    /** Reads a certificate from a PEM file. */
    public X509Certificate readPem(String path) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            return (X509Certificate) factory.generateCertificate(in);
        }
    }
}
