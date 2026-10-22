-- Dastakhat - initial schema (PostgreSQL 16)
--
-- All timestamps are stored as TIMESTAMPTZ (UTC). UUIDs are used for every primary key so
-- that the identifiers cannot be guessed. The audit table is append only - see
-- V2__audit_append_only.sql.

CREATE TABLE users (
    id            UUID PRIMARY KEY,
    email         VARCHAR(320) NOT NULL,
    name          VARCHAR(120) NOT NULL,
    password_hash VARCHAR(200) NOT NULL,
    role          VARCHAR(32)  NOT NULL DEFAULT 'sender',
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_users_email ON users (lower(email));

CREATE TABLE envelopes (
    id                UUID PRIMARY KEY,
    owner_id          UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title             VARCHAR(200) NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'draft',
    routing           VARCHAR(20)  NOT NULL DEFAULT 'sequential',
    expires_at        TIMESTAMPTZ  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at           TIMESTAMPTZ,
    completed_at      TIMESTAMPTZ,
    last_activity_at  TIMESTAMPTZ,
    original_sha256   VARCHAR(64),
    final_sha256      VARCHAR(64),
    pre_seal_sha256   VARCHAR(64),
    pages             INTEGER,
    void_reason       VARCHAR(500),
    CONSTRAINT ck_envelopes_status CHECK (status IN ('draft', 'sent', 'completed', 'declined', 'voided', 'expired')),
    CONSTRAINT ck_envelopes_routing CHECK (routing IN ('sequential', 'parallel'))
);

CREATE INDEX ix_envelopes_owner ON envelopes (owner_id, created_at DESC);
CREATE INDEX ix_envelopes_status ON envelopes (status, expires_at);

CREATE TABLE documents (
    id           UUID PRIMARY KEY,
    envelope_id  UUID         NOT NULL REFERENCES envelopes (id) ON DELETE CASCADE,
    kind         VARCHAR(20)  NOT NULL,
    storage_key  VARCHAR(80)  NOT NULL,
    filename     VARCHAR(255) NOT NULL,
    sha256       VARCHAR(64)  NOT NULL,
    pages        INTEGER,
    size_bytes   BIGINT       NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_documents_kind CHECK (kind IN ('original', 'working', 'final'))
);

CREATE UNIQUE INDEX ux_documents_storage_key ON documents (storage_key);
CREATE UNIQUE INDEX ux_documents_envelope_kind ON documents (envelope_id, kind);

CREATE TABLE recipients (
    id                        UUID PRIMARY KEY,
    envelope_id               UUID        NOT NULL REFERENCES envelopes (id) ON DELETE CASCADE,
    name                      VARCHAR(120) NOT NULL,
    email                     VARCHAR(320) NOT NULL,
    method                    VARCHAR(20)  NOT NULL DEFAULT 'electronic',
    routing_order             INTEGER      NOT NULL DEFAULT 1,
    status                    VARCHAR(20)  NOT NULL DEFAULT 'pending',
    token_hash                VARCHAR(64),
    token_expires_at          TIMESTAMPTZ,
    notified_at               TIMESTAMPTZ,
    viewed_at                 TIMESTAMPTZ,
    signed_at                 TIMESTAMPTZ,
    declined_at               TIMESTAMPTZ,
    decline_reason            VARCHAR(500),
    ip                        VARCHAR(64),
    user_agent                VARCHAR(400),
    consent_recorded_at       TIMESTAMPTZ,
    consent_text              VARCHAR(500),
    download_token_hash       VARCHAR(64),
    download_token_expires_at TIMESTAMPTZ,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_recipients_method CHECK (method IN ('electronic', 'dsc', 'esign_mock')),
    CONSTRAINT ck_recipients_status CHECK (status IN ('pending', 'notified', 'viewed', 'signed', 'declined'))
);

CREATE UNIQUE INDEX ux_recipients_token_hash ON recipients (token_hash) WHERE token_hash IS NOT NULL;
CREATE INDEX ix_recipients_envelope ON recipients (envelope_id, routing_order);
CREATE INDEX ix_recipients_download_token ON recipients (download_token_hash) WHERE download_token_hash IS NOT NULL;

CREATE TABLE fields (
    id           UUID PRIMARY KEY,
    envelope_id  UUID        NOT NULL REFERENCES envelopes (id) ON DELETE CASCADE,
    recipient_id UUID        NOT NULL REFERENCES recipients (id) ON DELETE CASCADE,
    type         VARCHAR(20) NOT NULL,
    page         INTEGER     NOT NULL,
    x            DOUBLE PRECISION NOT NULL,
    y            DOUBLE PRECISION NOT NULL,
    w            DOUBLE PRECISION NOT NULL,
    h            DOUBLE PRECISION NOT NULL,
    required     BOOLEAN     NOT NULL DEFAULT TRUE,
    value        VARCHAR(2000),
    label        VARCHAR(120),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_fields_type CHECK (type IN ('signature', 'initials', 'date', 'text', 'name')),
    CONSTRAINT ck_fields_bounds CHECK (x >= 0 AND y >= 0 AND w > 0 AND h > 0 AND x + w <= 1.0001 AND y + h <= 1.0001)
);

CREATE INDEX ix_fields_envelope ON fields (envelope_id, page);
CREATE INDEX ix_fields_recipient ON fields (recipient_id);

CREATE TABLE signatures (
    id                  UUID PRIMARY KEY,
    recipient_id        UUID        NOT NULL REFERENCES recipients (id) ON DELETE CASCADE,
    field_id            UUID        REFERENCES fields (id) ON DELETE SET NULL,
    kind                VARCHAR(20) NOT NULL,
    image_key           VARCHAR(80),
    typed_name          VARCHAR(200),
    signer_cert_pem     TEXT,
    signer_cert_sha256  VARCHAR(64),
    signer_cert_subject VARCHAR(300),
    cms_sha256          VARCHAR(64),
    signature_sha256    VARCHAR(64),
    validation_report   TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_signatures_kind CHECK (kind IN ('drawn', 'typed', 'dsc', 'esign_mock'))
);

CREATE INDEX ix_signatures_recipient ON signatures (recipient_id, created_at DESC);

CREATE TABLE signing_sessions (
    id                  UUID PRIMARY KEY,
    recipient_id        UUID        NOT NULL REFERENCES recipients (id) ON DELETE CASCADE,
    kind                VARCHAR(20) NOT NULL,
    prepared_key        VARCHAR(80) NOT NULL,
    document_digest     VARCHAR(64) NOT NULL,
    document_sha256     VARCHAR(64) NOT NULL,
    signed_attrs_der    BYTEA,
    txn                 VARCHAR(64),
    status              VARCHAR(20) NOT NULL DEFAULT 'prepared',
    expires_at          TIMESTAMPTZ NOT NULL,
    cert_pem            TEXT,
    cert_sha256         VARCHAR(64),
    signature_algorithm VARCHAR(40),
    byte_range          TEXT,
    cms_der             BYTEA,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    used_at             TIMESTAMPTZ,
    CONSTRAINT ck_signing_sessions_kind CHECK (kind IN ('dsc', 'esign_mock')),
    CONSTRAINT ck_signing_sessions_status CHECK (status IN ('prepared', 'finished', 'expired', 'failed'))
);

CREATE INDEX ix_signing_sessions_recipient ON signing_sessions (recipient_id, kind, created_at DESC);

CREATE TABLE audit_events (
    id          UUID PRIMARY KEY,
    envelope_id UUID        NOT NULL REFERENCES envelopes (id) ON DELETE CASCADE,
    seq         INTEGER     NOT NULL,
    type        VARCHAR(40) NOT NULL,
    actor       VARCHAR(200) NOT NULL,
    ip          VARCHAR(64),
    user_agent  VARCHAR(400),
    doc_sha256  VARCHAR(64),
    payload     TEXT,
    prev_hash   VARCHAR(64) NOT NULL,
    hash        VARCHAR(64) NOT NULL,
    at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_audit_events_envelope_seq UNIQUE (envelope_id, seq)
);

CREATE INDEX ix_audit_events_envelope ON audit_events (envelope_id, seq);
