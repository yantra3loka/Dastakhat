package com.dastakhat.documents;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    Optional<Document> findByEnvelopeIdAndKind(UUID envelopeId, DocumentKind kind);

    List<Document> findByEnvelopeId(UUID envelopeId);

    Optional<Document> findByStorageKey(String storageKey);
}
