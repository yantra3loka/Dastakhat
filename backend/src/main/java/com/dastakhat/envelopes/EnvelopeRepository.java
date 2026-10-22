package com.dastakhat.envelopes;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EnvelopeRepository extends JpaRepository<Envelope, UUID> {

    Page<Envelope> findByOwnerId(UUID ownerId, Pageable pageable);

    Page<Envelope> findByOwnerIdAndStatus(UUID ownerId, EnvelopeStatus status, Pageable pageable);

    @Query("select e from Envelope e where e.owner.id = :ownerId and e.status = :status and e.expiresAt < :now")
    List<Envelope> findExpired(@Param("ownerId") UUID ownerId, @Param("status") EnvelopeStatus status,
                                @Param("now") java.time.Instant now);

    Optional<Envelope> findByIdAndOwnerId(UUID id, UUID ownerId);

    @Query("select e from Envelope e where e.status = :status and e.expiresAt < :now")
    List<Envelope> findExpired(@Param("status") EnvelopeStatus status, @Param("now") java.time.Instant now);
}
