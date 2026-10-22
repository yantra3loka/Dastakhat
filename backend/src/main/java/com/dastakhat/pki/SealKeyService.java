package com.dastakhat.pki;

import com.dastakhat.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the development key material.
 *
 * <p>Everything that is loaded here belongs to the TEST CA. The private keys live outside
 * of the repository - the paths are configured through environment variables.</p>
 */
@Service
public class SealKeyService {

    private static final Logger log = LoggerFactory.getLogger(SealKeyService.class);

    private final AppProperties properties;
    private volatile PrivateKey privateKey;
    private volatile X509Certificate certificate;
    private volatile List<X509Certificate> chain = new ArrayList<>();
    private volatile List<X509Certificate> trustRoots = new ArrayList<>();

    public SealKeyService(AppProperties properties) {
        this.properties = properties;
    }

    /** Loads the seal key. Returns {@code false} when the development PKI is not present yet. */
    public boolean load() {
        try {
            Path keystore = Path.of(properties.getSealKeystore());
            if (!Files.exists(keystore)) {
                log.warn("The development seal keystore {} does not exist. "
                        + "Run the devpki tool to create the TEST certificates.", keystore);
                return false;
            }
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(keystore)) {
                store.load(in, properties.getSealKeystorePassword().toCharArray());
            }
            String alias = store.aliases().nextElement();
            this.privateKey = (PrivateKey) store.getKey(alias, properties.getSealKeystorePassword().toCharArray());
            this.certificate = (X509Certificate) store.getCertificate(alias);
            List<X509Certificate> chain = new ArrayList<>();
            for (java.security.cert.Certificate c : store.getCertificateChain(alias)) {
                if (c instanceof X509Certificate x509) {
                    chain.add(x509);
                }
            }
            this.chain = chain;
            this.trustRoots = loadTrustRoots();
            log.info("Loaded the development seal certificate {} (trust roots: {})",
                    certificate.getSubjectX500Principal(), trustRoots.size());
            return true;
        } catch (Exception e) {
            log.error("Cannot load the development seal keystore", e);
            return false;
        }
    }

    /** Reads the configured trust roots. */
    public List<X509Certificate> loadTrustRoots() {
        List<X509Certificate> roots = new ArrayList<>();
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            for (String path : properties.getTrustRoots()) {
                Path file = Path.of(path);
                if (!Files.exists(file)) {
                    continue;
                }
                try (InputStream in = Files.newInputStream(file)) {
                    roots.add((X509Certificate) factory.generateCertificate(in));
                }
            }
        } catch (Exception e) {
            log.error("Cannot read the trust roots", e);
        }
        return roots;
    }

    public boolean isAvailable() {
        return privateKey != null && certificate != null;
    }

    public PrivateKey getPrivateKey() {
        return privateKey;
    }

    public X509Certificate getCertificate() {
        return certificate;
    }

    public List<X509Certificate> getChain() {
        return chain;
    }

    public List<X509Certificate> getTrustRoots() {
        return trustRoots;
    }
}
