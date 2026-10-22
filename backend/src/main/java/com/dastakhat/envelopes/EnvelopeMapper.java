package com.dastakhat.envelopes;

import com.dastakhat.documents.Document;
import com.dastakhat.documents.DocumentRepository;
import com.dastakhat.fields.Field;
import com.dastakhat.recipients.Recipient;
import com.dastakhat.signing.Signature;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Maps the domain objects to the API models. */
@Component
public class EnvelopeMapper {

    private final DocumentRepository documents;

    public EnvelopeMapper(DocumentRepository documents) {
        this.documents = documents;
    }

    @Transactional(readOnly = true)
    public EnvelopeDtos.EnvelopeView toView(Envelope envelope,
                                            List<Recipient> recipients,
                                            List<Field> fields,
                                            Map<UUID, Signature> signatures) {
        Document original = documents.findByEnvelopeIdAndKind(envelope.getId(), com.dastakhat.documents.DocumentKind.ORIGINAL)
                .orElse(null);
        Document finalDocument = documents.findByEnvelopeIdAndKind(envelope.getId(),
                com.dastakhat.documents.DocumentKind.FINAL).orElse(null);

        List<EnvelopeDtos.RecipientView> recipientViews = new ArrayList<>();
        int signed = 0;
        for (Recipient recipient : recipients) {
            if (recipient.getStatus() == com.dastakhat.recipients.RecipientStatus.SIGNED) {
                signed++;
            }
            Signature signature = signatures.get(recipient.getId());
            recipientViews.add(new EnvelopeDtos.RecipientView(
                    recipient.getId(),
                    recipient.getName(),
                    recipient.getEmail(),
                    recipient.getMethod().value(),
                    recipient.getRoutingOrder(),
                    recipient.getStatus().value(),
                    recipient.getNotifiedAt(),
                    recipient.getViewedAt(),
                    recipient.getSignedAt(),
                    recipient.getTokenExpiresAt(),
                    signature == null ? null : new EnvelopeDtos.SignatureInfo(
                            signature.getId(),
                            signature.getKind().value(),
                            signature.getSignerCertSha256(),
                            signature.getCmsSha256(),
                            signature.getCreatedAt())));
        }

        List<EnvelopeDtos.FieldView> fieldViews = new ArrayList<>();
        for (Field field : fields) {
            fieldViews.add(new EnvelopeDtos.FieldView(
                    field.getId(),
                    field.getRecipient().getId(),
                    field.getType().value(),
                    field.getPage(),
                    field.getX(),
                    field.getY(),
                    field.getW(),
                    field.getH(),
                    field.isRequired(),
                    field.getLabel(),
                    field.getValue()));
        }

        EnvelopeDtos.DocumentInfo documentInfo = original == null ? null : new EnvelopeDtos.DocumentInfo(
                original.getFilename(), original.getSha256(), original.getPages(), original.getSizeBytes());

        return new EnvelopeDtos.EnvelopeView(
                envelope.getId(),
                envelope.getTitle(),
                envelope.getStatus().value(),
                envelope.getRouting().value(),
                envelope.getExpiresAt(),
                envelope.getCreatedAt(),
                envelope.getUpdatedAt(),
                envelope.getCompletedAt(),
                envelope.getLastActivityAt(),
                signed,
                recipients.size(),
                envelope.getOriginalSha256(),
                envelope.getFinalSha256(),
                envelope.getPreSealSha256(),
                envelope.getPages(),
                documentInfo,
                recipientViews,
                fieldViews,
                finalDocument != null);
    }
}
