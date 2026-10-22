package com.dastakhat.envelopes;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The request and response models of the envelope API. */
public final class EnvelopeDtos {

    private EnvelopeDtos() {
    }

    public record DocumentInfo(String filename, String sha256, Integer pages, long sizeBytes) { }

    public record SignatureInfo(UUID id, String kind, String signerCertSha256, String cmsSha256, Instant createdAt) { }

    public record RecipientView(UUID id,
                                String name,
                                String email,
                                String method,
                                int routingOrder,
                                String status,
                                Instant notifiedAt,
                                Instant viewedAt,
                                Instant signedAt,
                                Instant tokenExpiresAt,
                                SignatureInfo signature) { }

    public record FieldView(UUID id,
                            UUID recipientId,
                            String type,
                            int page,
                            double x,
                            double y,
                            double w,
                            double h,
                            boolean required,
                            String label,
                            String value) { }

    public record EnvelopeView(UUID id,
                               String title,
                               String status,
                               String routing,
                               Instant expiresAt,
                               Instant createdAt,
                               Instant updatedAt,
                               Instant completedAt,
                               Instant lastActivityAt,
                               int signedCount,
                               int recipientCount,
                               String originalSha256,
                               String finalSha256,
                               String preSealSha256,
                               Integer pages,
                               DocumentInfo document,
                               List<RecipientView> recipients,
                               List<FieldView> fields,
                               boolean finalAvailable) { }

    public record EnvelopePage(List<EnvelopeView> content, int page, int size, long totalElements, int totalPages) { }

    public record RecipientInput(@NotBlank @Size(max = 120) String name,
                                 @NotBlank @Email @Size(max = 320) String email,
                                 @NotBlank String method,
                                 @Min(1) Integer routingOrder) { }

    public record RecipientsRequest(@NotEmpty List<@Valid RecipientInput> recipients) { }

    public record FieldInput(@NotNull UUID recipientId,
                             @NotBlank String type,
                             @Min(1) int page,
                            @Min(0) double x,
                            @Min(0) double y,
                            @Min(0) double w,
                            @Min(0) double h,
                            Boolean required,
                            @Size(max = 120) String label) { }

    public record FieldsRequest(@NotNull List<@Valid FieldInput> fields) { }

    public record EnvelopeUpdateRequest(@Size(max = 200) String title,
                                        String routing,
                                        @Min(1) @Max(90) Integer expiresInDays,
                                        String status) { }
}
