-- The token of a recipient that has already signed (or whose envelope was voided) is moved
-- to this column. The active token in token_hash is cleared, so the link cannot be used a
-- second time, but the platform can still answer with "410 - this link has already been
-- used" instead of pretending that the link never existed.

ALTER TABLE recipients ADD COLUMN used_token_hash VARCHAR(64);

CREATE INDEX ix_recipients_used_token ON recipients (used_token_hash) WHERE used_token_hash IS NOT NULL;
