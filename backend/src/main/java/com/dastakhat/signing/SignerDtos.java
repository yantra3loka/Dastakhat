package com.dastakhat.signing;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The models of the signer API. */
public final class SignerDtos {

    private SignerDtos() {
    }

    /** The legal notice that every signer sees. */
    public static final String LEGAL_NOTICE = """
            Signing electronically means that you agree that the signature you place on this \
            document is legally binding for the document. An electronic signature captured as a \
            drawn or typed image is NOT a cryptographic digital signature and is not equivalent \
            to a DSC or Aadhaar eSign. The MOCK eSign provider is a simulation without any legal \
            standing.""";

    public record DocumentInfo(String filename, String sha256, Integer pages, long sizeBytes) { }

    public record FieldView(UUID id,
                            String type,
                            int page,
                            double x,
                            double y,
                            double w,
                            double h,
                            boolean required,
                            String label,
                            String value) { }

    public record SignerView(UUID envelopeId,
                             String envelopeTitle,
                             String senderName,
                             String senderEmail,
                             String recipientName,
                             String recipientEmail,
                             String method,
                             Instant expiresAt,
                             boolean consentRecorded,
                             DocumentInfo document,
                             List<FieldView> fields,
                             String documentUrl,
                             String legalNotice,
                             boolean mock) { }

    public record ConsentRequest(boolean consent, @Size(max = 500) String consentText) { }

    public record FieldValue(@NotNull UUID fieldId,
                             @Size(max = 2000) String value,
                             String signatureImageBase64) { }

    public record CompleteRequest(boolean consent, @NotNull List<FieldValue> fields) { }

    public record CompleteResponse(String status,
                                   UUID recipientId,
                                   Instant signedAt,
                                   String envelopeStatus,
                                   int nextRecipientsNotified) { }

    public record DscPrepareResponse(UUID sessionId,
                                     String toBeSignedBase64,
                                     String documentSha256,
                                     String documentName,
                                     String signatureAlgorithm,
                                     Instant expiresAt) { }

    public record DscFinishResponse(String status,
                                    String signatureSha256,
                                    boolean validated,
                                    boolean trusted,
                                    String envelopeStatus) { }

    public record EsignStartResponse(String txn,
                                     String espUrl,
                                     String requestXml,
                                     boolean mock,
                                     String banner) { }

    public record EsignCallbackResponse(String status, boolean validated, String envelopeStatus) { }
}
