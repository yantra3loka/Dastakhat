package com.dastakhat.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    List<AuditEvent> findByEnvelopeIdOrderBySeqAsc(UUID envelopeId);

    Optional<AuditEvent> findFirstByEnvelopeIdOrderBySeqDesc(UUID envelopeId);

    @Query("select coalesce(max(e.seq), 0) from AuditEvent e where e.envelope.id = :envelopeId")
    int maxSeq(@Param("envelopeId") UUID envelopeId);
}
