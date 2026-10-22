package com.dastakhat.signing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SigningSessionRepository extends JpaRepository<SigningSession, UUID> {

    Optional<SigningSession> findByIdAndRecipientId(UUID id, UUID recipientId);

    List<SigningSession> findByRecipientIdAndKind(UUID recipientId, SigningSession.Kind kind);

    Optional<SigningSession> findFirstByRecipientIdAndKindOrderByCreatedAtDesc(UUID recipientId,
                                                                                SigningSession.Kind kind);
}
