package com.dastakhat.fields;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FieldRepository extends JpaRepository<Field, UUID> {

    List<Field> findByEnvelopeIdOrderByPageAsc(UUID envelopeId);

    List<Field> findByRecipientId(UUID recipientId);

    void deleteByEnvelopeId(UUID envelopeId);
}
