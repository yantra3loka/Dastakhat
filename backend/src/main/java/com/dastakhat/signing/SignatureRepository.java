package com.dastakhat.signing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SignatureRepository extends JpaRepository<Signature, UUID> {

    List<Signature> findByRecipientId(UUID recipientId);

    Optional<Signature> findFirstByRecipientIdOrderByCreatedAtDesc(UUID recipientId);

    Optional<Signature> findByFieldId(UUID fieldId);
}
