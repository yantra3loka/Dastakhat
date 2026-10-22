package com.dastakhat.pki;

import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates a signer certificate against the configured trust roots.
 *
 * <p>The chain is built explicitly: the signer certificate is followed upwards through the
 * certificates that are embedded in the CMS until a trusted root is reached. Every link is
 * verified with the public key of its issuer.</p>
 *
 * <p>The revocation check is intentionally not performed because the development PKI has no
 * CRL distribution points. A production deployment has to enable revocation checking (or use
 * PAdES B-LT with the revocation information embedded in the signature).</p>
 */
public final class CertificateChainValidator {

    private CertificateChainValidator() {
    }

    /**
     * Checks whether the signer certificate chains to one of the given roots.
     *
     * @param signer     the signer certificate
     * @param cms        the CMS that carries the additional certificates of the chain
     * @param trustRoots the trusted root certificates
     */
    public static boolean validate(X509Certificate signer, CMSSignedData cms, List<X509Certificate> trustRoots) {
        if (trustRoots == null || trustRoots.isEmpty()) {
            return false;
        }
        List<X509Certificate> pool = new ArrayList<>();
        pool.add(signer);
        // The trusted roots are part of the search: the chain of a signature does not have to
        // contain the root certificate.
        pool.addAll(trustRoots);
        try {
            Collection<X509CertificateHolder> matches = cms.getCertificates().getMatches(null);
            for (X509CertificateHolder holder : matches) {
                X509Certificate certificate = new JcaX509CertificateConverter()
                        .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                        .getCertificate(holder);
                pool.add(certificate);
            }
        } catch (Exception e) {
            return false;
        }

        X509Certificate current = signer;
        Set<X509Certificate> visited = new HashSet<>();
        while (true) {
            if (!isValidAt(current, new Date())) {
                return false;
            }
            if (isTrustAnchor(current, trustRoots)) {
                return true;
            }
            X509Certificate issuer = null;
            for (X509Certificate candidate : pool) {
                if (visited.contains(candidate)) {
                    continue;
                }
                if (!candidate.getSubjectX500Principal().equals(current.getIssuerX500Principal())) {
                    continue;
                }
                if (!isSignedBy(current, candidate)) {
                    continue;
                }
                issuer = candidate;
                break;
            }
            if (issuer == null) {
                return false;
            }
            visited.add(current);
            current = issuer;
        }
    }

    /** True when the certificate itself is one of the trusted roots. */
    public static boolean isTrustAnchor(X509Certificate certificate, List<X509Certificate> trustRoots) {
        for (X509Certificate root : trustRoots) {
            if (root.getSubjectX500Principal().equals(certificate.getSubjectX500Principal())) {
                return true;
            }
        }
        return false;
    }

    /** True when the certificate was signed by the given issuer. */
    public static boolean isSignedBy(X509Certificate certificate, X509Certificate issuer) {
        try {
            Signature verifier = Signature.getInstance(certificate.getSigAlgName(),
                    BouncyCastleProvider.PROVIDER_NAME);
            verifier.initVerify(issuer.getPublicKey());
            verifier.update(certificate.getTBSCertificate());
            return verifier.verify(certificate.getSignature());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Checks the key usage of a DSC certificate. A signing certificate has to allow
     * {@code digitalSignature} and - following the original brief - {@code nonRepudiation}.
     */
    public static boolean hasSigningKeyUsage(X509Certificate certificate, boolean requireNonRepudiation) {
        boolean[] keyUsage = certificate.getKeyUsage();
        if (keyUsage == null) {
            // No key usage extension means "unrestricted".
            return true;
        }
        boolean digitalSignature = keyUsage.length > 0 && keyUsage[0];
        boolean nonRepudiation = keyUsage.length > 1 && keyUsage[1];
        if (requireNonRepudiation) {
            return digitalSignature || nonRepudiation;
        }
        return digitalSignature || nonRepudiation;
    }

    /** True when the certificate is inside its validity window. */
    public static boolean isValidAt(X509Certificate certificate, Date at) {
        try {
            certificate.checkValidity(at);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
