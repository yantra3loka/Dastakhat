package com.dastakhat.common;

import com.dastakhat.audit.AuditEventType;
import com.dastakhat.documents.DocumentKind;
import com.dastakhat.envelopes.EnvelopeStatus;
import com.dastakhat.envelopes.RoutingMode;
import com.dastakhat.fields.FieldType;
import com.dastakhat.recipients.RecipientStatus;
import com.dastakhat.recipients.SigningMethod;
import com.dastakhat.signing.SignatureKind;
import com.dastakhat.signing.SigningSession;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * The converters that store the enums in the database with the lower case values of the
 * data model ("draft", "sequential", ...) instead of the Java constant names.
 *
 * <p>The check constraints of the migrations use the same lower case values, so the database
 * and the API always agree.</p>
 */
public final class EnumConverters {

    private EnumConverters() {
    }

    @Converter
    public static class EnvelopeStatusConverter implements AttributeConverter<EnvelopeStatus, String> {
        @Override
        public String convertToDatabaseColumn(EnvelopeStatus attribute) {
            return attribute == null ? null : attribute.value();
        }

        @Override
        public EnvelopeStatus convertToEntityAttribute(String dbData) {
            return dbData == null ? null : EnvelopeStatus.fromValue(dbData);
        }
    }

    @Converter
    public static class RoutingModeConverter implements AttributeConverter<RoutingMode, String> {
        @Override
        public String convertToDatabaseColumn(RoutingMode attribute) {
            return attribute == null ? null : attribute.value();
        }

        @Override
        public RoutingMode convertToEntityAttribute(String dbData) {
            return dbData == null ? null : RoutingMode.fromValue(dbData);
        }
    }

    @Converter
    public static class RecipientStatusConverter implements AttributeConverter<RecipientStatus, String> {
        @Override
        public String convertToDatabaseColumn(RecipientStatus attribute) {
            return attribute == null ? null : attribute.value();
        }

        @Override
        public RecipientStatus convertToEntityAttribute(String dbData) {
            return dbData == null ? null : RecipientStatus.fromValue(dbData);
        }
    }

    @Converter
    public static class SigningMethodConverter implements AttributeConverter<SigningMethod, String> {
        @Override
        public String convertToDatabaseColumn(SigningMethod attribute) {
            return attribute == null ? null : attribute.value();
        }

        @Override
        public SigningMethod convertToEntityAttribute(String dbData) {
            return dbData == null ? null : SigningMethod.fromValue(dbData);
        }
    }

    @Converter
    public static class FieldTypeConverter implements AttributeConverter<FieldType, String> {
        @Override
        public String convertToDatabaseColumn(FieldType attribute) {
            return attribute == null ? null : attribute.value();
        }

        @Override
        public FieldType convertToEntityAttribute(String dbData) {
            return dbData == null ? null : FieldType.fromValue(dbData);
        }
    }

    @Converter
    public static class SignatureKindConverter implements AttributeConverter<SignatureKind, String> {
        @Override
        public String convertToDatabaseColumn(SignatureKind attribute) {
            return attribute == null ? null : attribute.value();
        }

        @Override
        public SignatureKind convertToEntityAttribute(String dbData) {
            return dbData == null ? null : SignatureKind.fromValue(dbData);
        }
    }

    @Converter
    public static class DocumentKindConverter implements AttributeConverter<DocumentKind, String> {
        @Override
        public String convertToDatabaseColumn(DocumentKind attribute) {
            return attribute == null ? null : attribute.value();
        }

        @Override
        public DocumentKind convertToEntityAttribute(String dbData) {
            return dbData == null ? null : DocumentKind.fromValue(dbData);
        }
    }

    @Converter
    public static class AuditEventTypeConverter implements AttributeConverter<AuditEventType, String> {
        @Override
        public String convertToDatabaseColumn(AuditEventType attribute) {
            return attribute == null ? null : attribute.value();
        }

        @Override
        public AuditEventType convertToEntityAttribute(String dbData) {
            return dbData == null ? null : AuditEventType.fromValue(dbData);
        }
    }

    @Converter
    public static class SessionKindConverter implements AttributeConverter<SigningSession.Kind, String> {
        @Override
        public String convertToDatabaseColumn(SigningSession.Kind attribute) {
            return attribute == null ? null : attribute.name().toLowerCase();
        }

        @Override
        public SigningSession.Kind convertToEntityAttribute(String dbData) {
            return dbData == null ? null : SigningSession.Kind.valueOf(dbData.toUpperCase());
        }
    }

    @Converter
    public static class SessionStatusConverter implements AttributeConverter<SigningSession.Status, String> {
        @Override
        public String convertToDatabaseColumn(SigningSession.Status attribute) {
            return attribute == null ? null : attribute.name().toLowerCase();
        }

        @Override
        public SigningSession.Status convertToEntityAttribute(String dbData) {
            return dbData == null ? null : SigningSession.Status.valueOf(dbData.toUpperCase());
        }
    }
}
