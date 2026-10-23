package com.dastakhat.mockesp;

import com.dastakhat.pki.XmlSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The MOCK eSign API.
 *
 * <p>The flow imitates the CCA eSign API 2.1: the application service provider sends a
 * signed XML request with the document hash, the provider shows a consent page with a test
 * OTP, signs the hash with a one time certificate and answers with a signed XML response.</p>
 *
 * <p>Error paths: <b>ESP-944</b> (the user cancelled), <b>ESP-946</b> (the page expired) and
 * <b>ESP-911</b> (the signature of the request could not be verified).</p>
 */
@RestController
@RequestMapping("/esp")
public class MockEspController {

    private static final Logger log = LoggerFactory.getLogger(MockEspController.class);

    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    /** The state of one signing request. */
    private static final class Request {
        private final String txn;
        private final String responseUrl;
        private final byte[] documentHash;
        private final String documentInfo;
        private final Instant createdAt = Instant.now();
        private String status = "consent_required";
        private String errorCode = "NA";
        private String errorMessage = "NA";

        private Request(String txn, String responseUrl, byte[] documentHash, String documentInfo) {
            this.txn = txn;
            this.responseUrl = responseUrl;
            this.documentHash = documentHash;
            this.documentInfo = documentInfo;
        }
    }

    private final Map<String, Request> requests = new ConcurrentHashMap<>();
    private final MockEspProperties properties;
    private final MockEspKeys keys;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public MockEspController(MockEspProperties properties, MockEspKeys keys) {
        this.properties = properties;
        this.keys = keys;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", "dastakhat-mock-esp");
        body.put("mock", true);
        body.put("keysReady", keys.isReady());
        return body;
    }

    /**
     * Accepts the signed request of the application service provider.
     *
     * <p>The XML signature of the request is verified with the certificate that is embedded
     * in the request. A request whose signature does not verify is rejected with ESP-911.</p>
     */
    @PostMapping(value = "/esign", consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    public ResponseEntity<Map<String, Object>> esign(@RequestBody byte[] xml) {
        return handleRequest(xml);
    }

    /** The same endpoint for the SPA, which posts the request as a form field. */
    @PostMapping(value = "/esign", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> esignForm(@RequestParam("requestXml") String xml) {
        return handleRequest(xml.getBytes(StandardCharsets.UTF_8));
    }

    /** The same endpoint for a JSON body. */
    @PostMapping(value = "/esign", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> esignJson(@RequestBody Map<String, String> body) {
        String xml = body == null ? null : body.get("requestXml");
        if (xml == null) {
            return error("ESP-911", "The request does not contain any XML.");
        }
        return handleRequest(xml.getBytes(StandardCharsets.UTF_8));
    }

    private ResponseEntity<Map<String, Object>> handleRequest(byte[] xml) {
        String txn;
        String responseUrl;
        byte[] documentHash;
        String documentInfo;
        try {
            Document document = parse(xml);
            Element root = document.getDocumentElement();
            txn = root.getAttribute("txn");
            responseUrl = root.getAttribute("responseUrl");

            // The signature of the request has to be valid, otherwise ESP-911.
            X509CertificateHolder holder = embeddedCertificate(xml);
            if (holder == null || !XmlSigner.verify(xml, holder.certificate.getPublicKey(), holder.certificate)) {
                return error("ESP-911", "The signature of the request could not be verified.");
            }
            Element docs = XmlSigner.child(root, "Docs");
            Element inputHash = docs == null ? null : XmlSigner.child(docs, "InputHash");
            if (inputHash == null) {
                return error("ESP-911", "The request does not contain a document hash.");
            }
            documentHash = hexToBytes(inputHash.getTextContent().trim());
            documentInfo = inputHash.getAttribute("docInfo");
        } catch (Exception e) {
            log.warn("The MOCK request could not be read", e);
            return error("ESP-911", "The request is not a valid eSign request: " + e.getMessage());
        }

        if (txn == null || txn.isBlank()) {
            return error("ESP-911", "The request does not contain a transaction id.");
        }
        if (requests.containsKey(txn)) {
            return error("ESP-911", "The transaction " + txn + " is already known.");
        }
        requests.put(txn, new Request(txn, responseUrl, documentHash, documentInfo));
        log.info("MOCK eSign request {} received (document hash of {} bytes)", txn, documentHash.length);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("txn", txn);
        body.put("status", "consent_required");
        body.put("mock", true);
        body.put("consentUrl", "/esp/otp/" + txn);
        body.put("testOtp", properties.getTestOtp());
        body.put("banner", MockEspBanner.BANNER);
        return ResponseEntity.ok(body);
    }

    /** The OTP page of the mock. It shows the fixed test OTP. */
    @GetMapping(value = "/otp/{txn}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> otpPage(@PathVariable String txn) {
        Request request = requests.get(txn);
        if (request == null) {
            return ResponseEntity.status(404).contentType(MediaType.TEXT_HTML)
                    .body(page("Unknown transaction", "<p>This transaction is unknown.</p>"));
        }
        if (isExpired(request)) {
            request.status = "expired";
            request.errorCode = "ESP-946";
            request.errorMessage = "The consent page has expired.";
            return ResponseEntity.status(410).contentType(MediaType.TEXT_HTML)
                    .body(page("ESP-946 - expired", "<p>The consent page has expired.</p>"));
        }
        String html = page("MOCK eSign - consent",
                """
                <p>This is a <strong>simulation</strong>. No Aadhaar authentication happens here.</p>
                <p>Document: %s</p>
                <p>Transaction: %s</p>
                <p>Test OTP (this is test data, it is not a real one-time password): <code>%s</code></p>
                <form method="post" action="/esp/otp/%s">
                  <label for="otp">Enter the test OTP</label>
                  <input id="otp" name="otp" inputmode="numeric" autocomplete="off" required>
                  <button type="submit">Sign the document (MOCK)</button>
                </form>
                <form method="post" action="/esp/otp/%s/cancel">
                  <button type="submit">Cancel (ESP-944)</button>
                </form>
                """.formatted(request.documentInfo, request.txn, properties.getTestOtp(), request.txn,
                request.txn));
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    /** The user confirms the consent with the test OTP. */
    @PostMapping("/otp/{txn}")
    public ResponseEntity<Map<String, Object>> confirm(@PathVariable String txn, @RequestParam("otp") String otp) {
        Request request = requests.get(txn);
        if (request == null) {
            return error("ESP-911", "Unknown transaction.");
        }
        if (isExpired(request)) {
            request.status = "expired";
            request.errorCode = "ESP-946";
            return error("ESP-946", "The consent page has expired.");
        }
        if (!properties.getTestOtp().equals(otp)) {
            return error("ESP-944", "The OTP is wrong.");
        }
        try {
            MockEspKeys.OneTimeSignature signature = keys.signDocumentHash(request.documentHash);
            byte[] response = buildResponse(request, signature);
            deliver(request, response);
            request.status = "signed";
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("txn", txn);
            body.put("status", "signed");
            body.put("mock", true);
            body.put("delivered", true);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.error("The MOCK signature failed", e);
            request.status = "failed";
            request.errorCode = "ESP-911";
            return error("ESP-911", "The signature could not be created: " + e.getMessage());
        }
    }

    /** The user cancels the consent. */
    @PostMapping("/otp/{txn}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable String txn) {
        Request request = requests.get(txn);
        if (request != null) {
            request.status = "cancelled";
            request.errorCode = "ESP-944";
            request.errorMessage = "The user cancelled the request.";
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("txn", txn);
        body.put("status", "cancelled");
        body.put("resCode", "ESP-944");
        body.put("mock", true);
        return ResponseEntity.ok(body);
    }

    /** The status of a transaction. */
    @GetMapping("/status/{txn}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String txn) {
        Request request = requests.get(txn);
        if (request == null) {
            return error("ESP-911", "Unknown transaction.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("txn", txn);
        body.put("status", request.status);
        body.put("resCode", request.errorCode);
        body.put("errMsg", request.errorMessage);
        body.put("mock", true);
        return ResponseEntity.ok(body);
    }

    // ------------------------------------------------------------- helpers

    private byte[] buildResponse(Request request, MockEspKeys.OneTimeSignature signature) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder().newDocument();

        Element root = document.createElement("EsignResp");
        root.setAttribute("status", "1");
        root.setAttribute("ts", TS_FORMAT.format(Instant.now()));
        root.setAttribute("txn", request.txn);
        root.setAttribute("resCode", "MOCK-7781");
        root.setAttribute("errCode", "NA");
        root.setAttribute("errMsg", "NA");
        document.appendChild(root);

        Element userCertificate = document.createElement("UserX509Certificate");
        userCertificate.setTextContent(Base64.getMimeEncoder().encodeToString(
                signature.certificate().getEncoded()));
        root.appendChild(userCertificate);

        Element signatures = document.createElement("Signatures");
        root.appendChild(signatures);
        Element docSignature = document.createElement("DocSignature");
        docSignature.setAttribute("id", "1");
        docSignature.setAttribute("sigHashAlgorithm", "SHA256");
        docSignature.setTextContent(Base64.getMimeEncoder().encodeToString(signature.cms()));
        signatures.appendChild(docSignature);

        // The response is signed with the key of the MOCK ESP.
        keys.signResponse(root);

        javax.xml.transform.TransformerFactory transformerFactory = javax.xml.transform.TransformerFactory.newInstance();
        transformerFactory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
        javax.xml.transform.Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "no");
        java.io.StringWriter writer = new java.io.StringWriter();
        transformer.transform(new javax.xml.transform.dom.DOMSource(document),
                new javax.xml.transform.stream.StreamResult(writer));
        return writer.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Posts the response back to the application service provider. */
    private void deliver(Request request, byte[] response) throws Exception {
        if (request.responseUrl == null || request.responseUrl.isBlank()) {
            throw new IllegalStateException("The request does not contain a response URL.");
        }
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(request.responseUrl))
                .header("Content-Type", "application/xml")
                .POST(HttpRequest.BodyPublishers.ofByteArray(response))
                .timeout(Duration.ofSeconds(20))
                .build();
        HttpResponse<String> result = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        log.info("The MOCK response for {} was delivered ({} {})", request.txn,
                result.statusCode(), result.body());
        if (result.statusCode() < 200 || result.statusCode() >= 300) {
            // The application service provider refused the response - the signing has failed.
            throw new IllegalStateException("The application service provider answered the callback with HTTP "
                    + result.statusCode());
        }
    }

    private boolean isExpired(Request request) {
        return request.createdAt.plus(properties.getRequestExpiry()).isBefore(Instant.now());
    }

    private ResponseEntity<Map<String, Object>> error(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "0");
        body.put("resCode", code);
        body.put("errMsg", message);
        body.put("mock", true);
        return ResponseEntity.badRequest().body(body);
    }

    private Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    private byte[] hexToBytes(String hex) {
        String clean = hex.replaceAll("\\s", "");
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private String page(String title, String content) {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <title>%s</title>
                  <style>
                    body { font-family: system-ui, sans-serif; background: #fff8f0; color: #1f2937; margin: 0;
                           padding: 3rem 1rem; }
                    .card { max-width: 560px; margin: 0 auto; background: #fff; border: 1px solid #f3d9b8;
                            border-radius: 14px; padding: 2rem; box-shadow: 0 10px 30px rgba(180, 120, 40, .12); }
                    .banner { background: #f59e0b; color: #1f2937; padding: .75rem 1rem; border-radius: 10px;
                              font-weight: 700; letter-spacing: .04em; margin-bottom: 1.5rem; }
                    h1 { font-size: 1.25rem; margin-top: 0; }
                    code { background: #f3f4f6; padding: .15rem .4rem; border-radius: 6px; }
                    input { padding: .6rem .7rem; border: 1px solid #d1d5db; border-radius: 8px; width: 100%%;
                            margin: .5rem 0 1rem; }
                    button { background: #2563eb; color: #fff; border: 0; border-radius: 8px; padding: .65rem 1.1rem;
                             font-size: .95rem; cursor: pointer; }
                    form { margin: 0 0 .75rem; }
                  </style>
                </head>
                <body>
                  <div class="card">
                    <div class="banner">MOCK - simulated Aadhaar eSign. No real authentication happens here.</div>
                    <h1>%s</h1>
                    %s
                  </div>
                </body>
                </html>
                """.formatted(title, title, content);
    }

    /** The certificate that is embedded in a signed XML. */
    private record X509CertificateHolder(java.security.cert.X509Certificate certificate) { }

    private X509CertificateHolder embeddedCertificate(byte[] xml) {
        try {
            java.security.cert.X509Certificate certificate = XmlSigner.embeddedCertificate(xml);
            return certificate == null ? null : new X509CertificateHolder(certificate);
        } catch (Exception e) {
            return null;
        }
    }
}
