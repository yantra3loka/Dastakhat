package com.dastakhat.pki;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.keyinfo.X509Data;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.security.Key;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;

/**
 * XML-DSig signing and verification with the XML signature API of the JDK.
 *
 * <p>This is the Java replacement for the {@code signxml + lxml} part of the original brief.
 * The signature is an enveloped signature over the whole document, so the receiving side can
 * verify the exact bytes that were sent.</p>
 *
 * <p>The keys are TEST keys of the MOCK CA. The mock has no legal standing and never
 * contacts UIDAI or a real eSign provider.</p>
 */
public final class XmlSigner {

    private XmlSigner() {
    }

    /** Signs the given element in place (the signature becomes a child of the element). */
    public static void sign(Element element, PrivateKey privateKey, X509Certificate certificate)
            throws Exception {
        XMLSignatureFactory factory = XMLSignatureFactory.getInstance("DOM");
        Reference reference = factory.newReference(
                "",
                factory.newDigestMethod(DigestMethod.SHA256, null),
                Collections.singletonList(factory.newTransform(Transform.ENVELOPED,
                        (TransformParameterSpec) null)),
                null, null);
        SignedInfo signedInfo = factory.newSignedInfo(
                factory.newCanonicalizationMethod(CanonicalizationMethod.INCLUSIVE,
                        (C14NMethodParameterSpec) null),
                factory.newSignatureMethod("http://www.w3.org/2001/04/xmldsig-more#rsa-sha256", null),
                Collections.singletonList(reference),
                null);

        KeyInfoFactory keyInfoFactory = factory.getKeyInfoFactory();
        X509Data x509Data = keyInfoFactory.newX509Data(Collections.singletonList(certificate));
        KeyInfo keyInfo = keyInfoFactory.newKeyInfo(Collections.singletonList(x509Data));

        DOMSignContext context = new DOMSignContext(privateKey, element);
        context.setDefaultNamespacePrefix(null);
        context.setIdAttributeNS(element, null, "Id");

        XMLSignature signature = factory.newXMLSignature(signedInfo, keyInfo);
        signature.sign(context);
    }

    /**
     * Verifies the enveloped signature of the given element.
     *
     * @param xml            the raw bytes that were signed
     * @param trustedKey     the public key (or certificate) the signature has to verify with
     * @param trustedCert    the certificate that was embedded in the signature, may be null
     */
    public static boolean verify(byte[] xml, Key trustedKey, X509Certificate trustedCert) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        org.w3c.dom.Document document = builder.parse(new InputSource(new ByteArrayInputStream(xml)));

        Element root = document.getDocumentElement();
        if (root == null) {
            return false;
        }
        XMLSignatureFactory signatureFactory = XMLSignatureFactory.getInstance("DOM");
        DOMValidateContext context = new DOMValidateContext(trustedKey, root);
        context.setIdAttributeNS(root, null, "Id");
        XMLSignature signature = signatureFactory.unmarshalXMLSignature(context);
        if (!signature.validate(context)) {
            return false;
        }
        if (trustedCert != null) {
            // The embedded certificate has to be the one we expect.
            NodeList dataNodes = root.getElementsByTagNameNS(XMLSignature.XMLNS, "X509Certificate");
            boolean found = false;
            for (int i = 0; i < dataNodes.getLength(); i++) {
                String value = dataNodes.item(i).getTextContent().replaceAll("\\s", "");
                byte[] der = java.util.Base64.getMimeDecoder().decode(value);
                java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
                X509Certificate embedded = (X509Certificate) cf.generateCertificate(
                        new ByteArrayInputStream(der));
                if (embedded.equals(trustedCert)) {
                    found = true;
                    break;
                }
            }
            return found;
        }
        return true;
    }

    /** Extracts the certificate that is embedded in an enveloped signature. */
    public static X509Certificate embeddedCertificate(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        org.w3c.dom.Document document = builder.parse(new InputSource(new ByteArrayInputStream(xml)));
        NodeList dataNodes = document.getDocumentElement().getElementsByTagNameNS(XMLSignature.XMLNS,
                "X509Certificate");
        if (dataNodes.getLength() == 0) {
            return null;
        }
        String value = dataNodes.item(0).getTextContent().replaceAll("\\s", "");
        byte[] der = java.util.Base64.getMimeDecoder().decode(value);
        java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
    }

    /** Returns the first child element with the given local name. */
    public static Element child(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && localName.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }
}
