package com.dastakhat.pki;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Set;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.CMSAttributes;
import org.bouncycastle.asn1.cms.CMSObjectIdentifiers;
import org.bouncycastle.asn1.cms.ContentInfo;
import org.bouncycastle.asn1.cms.IssuerAndSerialNumber;
import org.bouncycastle.asn1.cms.SignedData;
import org.bouncycastle.asn1.cms.SignerIdentifier;
import org.bouncycastle.asn1.cms.SignerInfo;
import org.bouncycastle.asn1.cms.Time;
import org.bouncycastle.asn1.ess.ESSCertIDv2;
import org.bouncycastle.asn1.ess.SigningCertificateV2;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.SignerInformationVerifier;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.pkcs.PKCSException;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.Provider;
import java.security.Security;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Date;

/**
 * Builds and verifies CMS / PKCS#7 detached signatures (PAdES baseline).
 *
 * <p>This is the Java replacement for the {@code pyHanko} part of the original brief. The
 * bytes that have to be signed are the DER encoded CMS signed attributes, exactly the same
 * contract that pyHanko uses for deferred signing, so the local DSC agent stays
 * interchangeable.</p>
 *
 * <p>The CMS is assembled explicitly instead of using {@code CMSSignedDataGenerator}: a PDF
 * signature is a <em>detached</em> CMS whose message digest attribute must be the digest of
 * the PDF byte range, not the digest of that digest. Building the ASN.1 by hand keeps that
 * relationship explicit and verifiable.</p>
 */
public final class CmsBuilder {

    /** SHA-256 with RSA (PKCS#1 v1.5) - what a SoftHSM token produces with CKM_SHA256_RSA_PKCS. */
    public static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

    private static final BouncyCastleProvider BC = new BouncyCastleProvider();

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BC);
        }
    }

    private CmsBuilder() {
    }

    /** The prepared signature: the attributes that have to be signed and the digest of the document. */
    public record Prepared(byte[] signedAttributesDer,
                           byte[] documentDigest,
                           X509Certificate certificate,
                           List<X509Certificate> chain,
                           String signatureAlgorithm,
                           Instant signingTime) { }

    /**
     * Prepares the signed attributes for a document digest.
     *
     * @param documentDigest SHA-256 of the bytes that are covered by the signature
     */
    public static Prepared prepare(byte[] documentDigest,
                                   X509Certificate certificate,
                                   List<X509Certificate> chain,
                                   Instant signingTime) {
        AlgorithmIdentifier digestAlgorithm =
                new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256, DERNull.INSTANCE);

        byte[] certHash;
        try {
            // PAdES (ETSI.CAdES.detached) requires the ESS signing certificate v2 attribute.
            certHash = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        } catch (Exception e) {
            throw new IllegalArgumentException("The signer certificate cannot be encoded", e);
        }

        ASN1EncodableVector attributes = new ASN1EncodableVector();
        attributes.add(new Attribute(CMSAttributes.contentType, new DERSet(CMSObjectIdentifiers.data)));
        attributes.add(new Attribute(CMSAttributes.messageDigest, new DERSet(new DEROctetString(documentDigest))));
        attributes.add(new Attribute(CMSAttributes.signingTime, new DERSet(new Time(Date.from(signingTime)))));

        SigningCertificateV2 signingCertificate = new SigningCertificateV2(new ESSCertIDv2[]{new ESSCertIDv2(certHash)});
        attributes.add(new Attribute(PKCSObjectIdentifiers.id_aa_signingCertificateV2, new DERSet(signingCertificate)));

        // A DER SET sorts its elements, so the encoding is stable no matter in which order the
        // attributes were added. The very same bytes are signed and embedded.
        ASN1Set signedAttributes = new DERSet(attributes);
        byte[] signedAttributesDer;
        try {
            signedAttributesDer = signedAttributes.getEncoded();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot encode the signed attributes", e);
        }

        List<X509Certificate> fullChain = chain == null ? new ArrayList<>() : new ArrayList<>(chain);
        fullChain.removeIf(c -> c.getSubjectX500Principal().equals(certificate.getSubjectX500Principal())
                && c.getSerialNumber().equals(certificate.getSerialNumber()));

        return new Prepared(signedAttributesDer, documentDigest, certificate, fullChain,
                SIGNATURE_ALGORITHM, signingTime);
    }

    /**
     * Assembles the CMS SignedData around a signature that was produced elsewhere (for
     * example by a DSC token).
     */
    public static byte[] assemble(Prepared prepared, byte[] rawSignature) throws CMSException {
        return assemble(prepared.signedAttributesDer(), rawSignature, prepared.certificate(), prepared.chain());
    }

    /**
     * Assembles a CMS around an already prepared set of signed attributes. The attributes are
     * taken verbatim so that the signature stays valid.
     */
    public static byte[] assemble(byte[] signedAttributesDer,
                                  byte[] rawSignature,
                                  X509Certificate certificate,
                                  List<X509Certificate> chain) throws CMSException {
        try {
            X500Name issuer = new X500Name(certificate.getIssuerX500Principal().getName());
            IssuerAndSerialNumber sid = new IssuerAndSerialNumber(issuer, certificate.getSerialNumber());

            AlgorithmIdentifier digestAlgorithm =
                    new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256, DERNull.INSTANCE);
            AlgorithmIdentifier signatureAlgorithm =
                    new AlgorithmIdentifier(PKCSObjectIdentifiers.rsaEncryption, DERNull.INSTANCE);

            // The signed attributes are re-read from the bytes that were handed to the signer so
            // that the embedded and the signed bytes are guaranteed to be identical.
            ASN1Set signedAttributes = ASN1Set.getInstance(ASN1Primitive.fromByteArray(signedAttributesDer));

            SignerInfo signerInfo = new SignerInfo(new SignerIdentifier(sid), digestAlgorithm,
                    signedAttributes, signatureAlgorithm, new DEROctetString(rawSignature), null);

            ASN1EncodableVector certVector = new ASN1EncodableVector();
            certVector.add(org.bouncycastle.asn1.x509.Certificate.getInstance(
                    org.bouncycastle.asn1.ASN1Sequence.getInstance(certificate.getEncoded())));
            for (X509Certificate chainCert : chain == null ? List.<X509Certificate>of() : chain) {
                certVector.add(org.bouncycastle.asn1.x509.Certificate.getInstance(
                        org.bouncycastle.asn1.ASN1Sequence.getInstance(chainCert.getEncoded())));
            }

            ASN1EncodableVector digestVector = new ASN1EncodableVector();
            digestVector.add(digestAlgorithm);
            ASN1EncodableVector signerVector = new ASN1EncodableVector();
            signerVector.add(signerInfo);

            SignedData signedData = new SignedData(
                    new DERSet(digestVector),
                    new ContentInfo(CMSObjectIdentifiers.data, null),
                    new DERSet(certVector),
                    new DERSet(),
                    new DERSet(signerVector));

            return new ContentInfo(CMSObjectIdentifiers.signedData, signedData).getEncoded();
        } catch (IOException | java.security.cert.CertificateEncodingException e) {
            throw new CMSException("Cannot assemble the CMS signature", e);
        }
    }

    /** The result of a verification. */
    public record Verification(boolean signatureValid,
                               boolean digestMatches,
                               boolean certificateValid,
                               boolean trusted,
                               String signerSubject,
                               String digestAlgorithm,
                               String signingTime,
                               List<String> messages) { }

    /**
     * Verifies a detached CMS signature against a document digest.
     *
     * @param cmsDer          the DER encoded CMS SignedData
     * @param documentDigest  the SHA-256 digest of the signed bytes
     * @param trustRoots      the trusted roots (may be empty - then the chain is not checked)
     */
    public static Verification verify(byte[] cmsDer, byte[] documentDigest, List<X509Certificate> trustRoots) {
        List<String> messages = new ArrayList<>();
        boolean signatureValid = false;
        boolean digestMatches = false;
        boolean certificateValid = false;
        boolean trusted = false;
        String subject = null;
        String digestAlgorithm = null;
        String signingTime = null;

        try {
            org.bouncycastle.cms.CMSSignedData signedData = new org.bouncycastle.cms.CMSSignedData(cmsDer);
            var signers = signedData.getSignerInfos().getSigners();
            if (signers.size() != 1) {
                messages.add("Expected exactly one signer but found " + signers.size() + ".");
                return new Verification(false, false, false, false, null, null, null, messages);
            }
            SignerInformation signer = signers.iterator().next();
            digestAlgorithm = signer.getDigestAlgOID();

            // 1. The message digest attribute must equal the digest of the signed bytes. The
            // attribute is read directly because BouncyCastle only exposes the digest of the
            // content after a verification and the content of a detached signature is absent.
            byte[] embeddedDigest = extractMessageDigest(signer);
            if (embeddedDigest != null && MessageDigest.isEqual(embeddedDigest, documentDigest)) {
                digestMatches = true;
            } else {
                messages.add("The message digest attribute does not match the digest of the signed bytes.");
            }

            // 2. The signature over the signed attributes must verify with the signer certificate.
            // The certificate of the signer is looked up by its issuer and serial number.
            // (SignerId is a raw Selector in BouncyCastle, so the certificates are searched
            // manually instead of using getMatches.)
            Collection<X509CertificateHolder> certificates = signedData.getCertificates().getMatches(null);
            org.bouncycastle.cms.SignerId signerId = signer.getSID();
            X509CertificateHolder holder = null;
            for (X509CertificateHolder candidate : certificates) {
                if (candidate.getIssuer().equals(signerId.getIssuer())
                        && candidate.getSerialNumber().equals(signerId.getSerialNumber())) {
                    holder = candidate;
                    break;
                }
            }
            if (holder == null) {
                messages.add("The certificate of the signer is not part of the CMS.");
                return new Verification(false, digestMatches, false, false, null, digestAlgorithm, null, messages);
            }
            X509Certificate certificate = new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
                    .setProvider(BC).getCertificate(holder);
            subject = certificate.getSubjectX500Principal().getName();
            signingTime = extractSigningTime(signer);

            SignerInformationVerifier verifier = new JcaSimpleSignerInfoVerifierBuilder()
                    .setProvider(BC)
                    .build(certificate);
            signatureValid = verifyDetachedSignature(signer, certificate);
            if (!signatureValid) {
                messages.add("The cryptographic signature does not verify.");
            }

            // 3. The certificate itself must be valid at the signing time.
            Date at = signingTime == null ? new Date() : Date.from(Instant.parse(signingTime));
            certificateValid = true;
            try {
                certificate.checkValidity(at);
            } catch (Exception e) {
                certificateValid = false;
                messages.add("The signer certificate is not valid at the signing time: " + e.getMessage());
            }

            // 4. The chain has to lead to one of the trusted roots.
            if (trustRoots != null && !trustRoots.isEmpty()) {
                trusted = CertificateChainValidator.validate(certificate, signedData, trustRoots);
                if (!trusted) {
                    messages.add("The signer certificate does not chain to a trusted root.");
                }
            } else {
                messages.add("No trust roots are configured - the chain could not be checked.");
            }
        } catch (Exception e) {
            messages.add("Verification failed: " + e.getMessage());
        }
        return new Verification(signatureValid, digestMatches, certificateValid, trusted, subject,
                digestAlgorithm, signingTime, messages);
    }

    /**
     * Verifies the signature of a detached CMS.
     *
     * <p>BouncyCastle's {@code SignerInformation.verify()} cannot be used here: it always
     * recomputes the message digest from the encapsulated content and a detached signature
     * has no content. The PDF signature is verified the way it is defined: the signature is
     * computed over the DER encoding of the signed attributes and the message digest
     * attribute carries the digest of the signed bytes.</p>
     */
    private static boolean verifyDetachedSignature(SignerInformation signer, X509Certificate certificate) {
        try {
            String digestOid = signer.getDigestAlgOID();
            String encryptionOid = signer.getEncryptionAlgOID();
            if (!NISTObjectIdentifiers.id_sha256.getId().equals(digestOid)) {
                return false;
            }
            if (!PKCSObjectIdentifiers.rsaEncryption.getId().equals(encryptionOid)) {
                return false;
            }
            byte[] signedAttributes = signer.getEncodedSignedAttributes();
            if (signedAttributes == null) {
                return false;
            }
            java.security.Signature verifier = java.security.Signature.getInstance(
                    "SHA256withRSA", BC);
            verifier.initVerify(certificate.getPublicKey());
            verifier.update(signedAttributes);
            return verifier.verify(signer.getSignature());
        } catch (Exception e) {
            return false;
        }
    }

    /** Reads the message digest attribute of the signed attributes. */
    private static byte[] extractMessageDigest(SignerInformation signer) {
        try {
            var attrs = signer.getSignedAttributes();
            if (attrs == null) {
                return null;
            }
            var attr = attrs.get(CMSAttributes.messageDigest);
            if (attr == null || attr.getAttrValues().size() != 1) {
                return null;
            }
            Object value = attr.getAttrValues().getObjectAt(0);
            if (value instanceof org.bouncycastle.asn1.ASN1OctetString octetString) {
                return octetString.getOctets();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractSigningTime(SignerInformation signer) {
        try {
            var attrs = signer.getSignedAttributes();
            if (attrs == null) {
                return null;
            }
            var attr = attrs.get(CMSAttributes.signingTime);
            if (attr == null || attr.getAttrValues().size() != 1) {
                return null;
            }
            Time time = Time.getInstance(attr.getAttrValues().getObjectAt(0));
            return time.getDate().toInstant().toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Provides the BouncyCastle provider instance. */
    public static Provider provider() {
        return BC;
    }

    /** Kept for symmetry with the other helpers. */
    static PKCSException unused() {
        return new PKCSException("unused");
    }
}
