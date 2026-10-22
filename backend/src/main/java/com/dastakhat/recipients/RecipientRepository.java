package com.dastakhat.recipients;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecipientRepository extends JpaRepository<Recipient, UUID> {

    List<Recipient> findByEnvelopeIdOrderByRoutingOrderAsc(UUID envelopeId);

    Optional<Recipient> findByTokenHash(String tokenHash);

    boolean existsByUsedTokenHash(String usedTokenHash);

    Optional<Recipient> findByIdAndEnvelopeId(UUID id, UUID envelopeId);

    List<Recipient> findByEnvelopeIdAndStatus(UUID envelopeId, RecipientStatus status);

    boolean existsByEnvelopeIdAndEmailIgnoreCase(UUID envelopeId, String email);
}
