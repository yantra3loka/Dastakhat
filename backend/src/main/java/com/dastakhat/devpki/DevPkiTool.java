package com.dastakhat.devpki;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/**
 * Creates the <b>TEST</b> certificates of the development installation.
 *
 * <p>Everything that is generated here is a throwaway test artefact. The private keys are
 * written into {@code devpki/out} which is git ignored, they must never be used outside of a
 * development machine and they have no legal standing.</p>
 *
 * <p>Usage:</p>
 * <pre>
 *   java ... com.dastakhat.devpki.DevPkiTool all [outputDirectory]
 *   java ... com.dastakhat.devpki.DevPkiTool issue-dsc &lt;spki.der&gt; &lt;output.pem&gt; [caKeystore] [password]
 * </pre>
 */
public final class DevPkiTool {

    private static final BouncyCastleProvider BC = new BouncyCastleProvider();
    private static final String KEYSTORE_PASSWORD = "changeit";
    private static final Duration VALIDITY = Duration.ofDays(365);
    private static final SecureRandom RANDOM = new SecureRandom();

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BC);
        }
    }

    private DevPkiTool() {
    }

    public static void main(String[] args) throws Exception {
        String command = args.length > 0 ? args[0] : "all";
        switch (command) {
            case "all" -> {
                Path out = Path.of(args.length > 1 ? args[1] : "out").toAbsolutePath().normalize();
                Files.createDirectories(out);
                generateAll(out);
            }
            case "issue-dsc" -> {
                if (args.length < 3) {
                    System.err.println("usage: issue-dsc <spki.der> <output.pem> [caKeystore] [password]");
                    System.exit(2);
                }
                Path spki = Path.of(args[1]);
                Path out = Path.of(args[2]);
                Path caKeystore = Path.of(args.length > 3 ? args[3] : "out/platform-ca.p12");
                String password = args.length > 4 ? args[4] : KEYSTORE_PASSWORD;
                issueDscCertificate(spki, out, caKeystore, password);
            }
            default -> {
                System.err.println("Unknown command: " + command);
                System.exit(2);
            }
        }
    }

    /** Creates the whole test PKI. */
    public static void generateAll(Path out) throws Exception {
        System.out.println("Creating the Dastakhat TEST PKI in " + out);

        // 1. The root CA that signs the platform seal and the DSC certificates.
        KeyPair rootKeys = generateKeyPair();
        X509Certificate root = createCa(rootKeys, "CN=Dastakhat TEST Root CA, O=Dastakhat, C=IN",
                rootKeys);
        writePem(out.resolve("test-root-ca.pem"), root);
        writeKeystore(out.resolve("platform-ca.p12"), "root", rootKeys.getPrivate(), new Certificate[]{root});

        // 2. The platform seal certificate (the key that signs the final document).
        KeyPair sealKeys = generateKeyPair();
        X509Certificate seal = createSigned(sealKeys.getPublic(), rootKeys.getPrivate(), root,
                "CN=Dastakhat Platform Seal (TEST CA), O=Dastakhat, C=IN",
                false, false);
        writePem(out.resolve("platform-seal-cert.pem"), seal);
        writeKeystore(out.resolve("platform-seal.p12"), "platform-seal", sealKeys.getPrivate(),
                new Certificate[]{seal, root});

        // 3. The DSC signer certificate (digitalSignature + nonRepudiation).
        KeyPair dscKeys = generateKeyPair();
        X509Certificate dsc = createSigned(dscKeys.getPublic(), rootKeys.getPrivate(), root,
                "CN=Dastakhat DSC Signer (TEST CA), O=Dastakhat, C=IN", false, true);
        writePem(out.resolve("dsc-signer-cert.pem"), dsc);
        writeKeystore(out.resolve("dsc-signer.p12"), "dsc-signer", dscKeys.getPrivate(),
                new Certificate[]{dsc, root});
        writePublicKey(out.resolve("dsc-signer-pubkey.der"), dscKeys.getPublic());

        // 4. The MOCK eSign CA and the two keys of the MOCK flow.
        KeyPair mockCaKeys = generateKeyPair();
        X509Certificate mockCa = createCa(mockCaKeys, "CN=Dastakhat MOCK eSign CA (TEST), O=Dastakhat, C=IN",
                mockCaKeys);
        writePem(out.resolve("mock-ca.pem"), mockCa);
        writeKeystore(out.resolve("mock-ca.p12"), "mock-ca", mockCaKeys.getPrivate(),
                new Certificate[]{mockCa});

        KeyPair aspKeys = generateKeyPair();
        X509Certificate asp = createSigned(aspKeys.getPublic(), mockCaKeys.getPrivate(), mockCa,
                "CN=Dastakhat MOCK ASP (TEST), O=Dastakhat, C=IN", false, false);
        writePem(out.resolve("mock-asp-cert.pem"), asp);
        writeKeystore(out.resolve("mock-asp.p12"), "mock-asp", aspKeys.getPrivate(),
                new Certificate[]{asp, mockCa});

        KeyPair espKeys = generateKeyPair();
        X509Certificate esp = createSigned(espKeys.getPublic(), mockCaKeys.getPrivate(), mockCa,
                "CN=Dastakhat MOCK ESP (TEST), O=Dastakhat, C=IN", false, false);
        writePem(out.resolve("mock-esp-cert.pem"), esp);
        writeKeystore(out.resolve("mock-esp.p12"), "mock-esp", espKeys.getPrivate(),
                new Certificate[]{esp, mockCa});

        System.out.println("Done. The private keys are test data and must never leave this machine.");
    }

    /**
     * Issues a DSC certificate for a public key that was generated inside a PKCS#11 token.
     * The private key never leaves the token.
     */
    public static void issueDscCertificate(Path spkiFile, Path outFile, Path caKeystore, String password)
            throws Exception {
        byte[] spki = Files.readAllBytes(spkiFile);
        RSAPublicKey publicKey = (RSAPublicKey) java.security.KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(spki));

        KeyStore store = KeyStore.getInstance("PKCS12");
        try (java.io.InputStream in = Files.newInputStream(caKeystore)) {
            store.load(in, password.toCharArray());
        }
        String alias = store.aliases().nextElement();
        PrivateKey caKey = (PrivateKey) store.getKey(alias, password.toCharArray());
        X509Certificate ca = (X509Certificate) store.getCertificate(alias);

        X509Certificate certificate = createSigned(publicKey, caKey, ca,
                "CN=Dastakhat DSC Signer (TEST CA), O=Dastakhat, C=IN", false, true);
        writePem(outFile, certificate);
        System.out.println("Wrote " + outFile);
    }

    // ------------------------------------------------------------- helpers

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", BC);
        generator.initialize(2048, RANDOM);
        return generator.generateKeyPair();
    }

    private static X509Certificate createCa(KeyPair keys, String dn, KeyPair caKeys) throws Exception {
        X500Name subject = new X500Name(dn);
        Instant now = Instant.now();
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, BigInteger.valueOf(System.currentTimeMillis()), Date.from(now),
                Date.from(now.plus(VALIDITY)), subject, keys.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign | KeyUsage.digitalSignature));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").setProvider(BC).build(keys.getPrivate());
        return new JcaX509CertificateConverter().setProvider(BC).getCertificate(builder.build(signer));
    }

    private static X509Certificate createSigned(java.security.PublicKey publicKey, PrivateKey issuerKey,
                                                X509Certificate issuer, String dn,
                                                boolean ca, boolean dsc) throws Exception {
        X500Name subject = new X500Name(dn);
        Instant now = Instant.now();
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                issuer, BigInteger.valueOf(Math.abs(new java.util.Random().nextLong()) + 1),
                Date.from(now), Date.from(now.plus(VALIDITY)), subject, publicKey);
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
        int usage = KeyUsage.digitalSignature;
        if (dsc) {
            // pyHanko (and every strict validator) expects nonRepudiation for a signer.
            usage |= KeyUsage.nonRepudiation;
        } else {
            usage |= KeyUsage.nonRepudiation;
        }
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(usage));
        builder.addExtension(Extension.extendedKeyUsage, false,
                new ExtendedKeyUsage(KeyPurposeId.id_kp_emailProtection));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").setProvider(BC).build(issuerKey);
        return new JcaX509CertificateConverter().setProvider(BC).getCertificate(builder.build(signer));
    }

    private static void writePem(Path file, X509Certificate certificate) throws Exception {
        String pem = "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(certificate.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
        Files.writeString(file, pem);
        System.out.println("Wrote " + file);
    }

    private static void writeKeystore(Path file, String alias, PrivateKey key, Certificate[] chain)
            throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry(alias, key, KEYSTORE_PASSWORD.toCharArray(), chain);
        try (OutputStream out = Files.newOutputStream(file)) {
            store.store(out, KEYSTORE_PASSWORD.toCharArray());
        }
        System.out.println("Wrote " + file);
    }

    private static void writePublicKey(Path file, java.security.PublicKey key) throws Exception {
        Files.write(file, key.getEncoded());
        System.out.println("Wrote " + file);
    }
}
