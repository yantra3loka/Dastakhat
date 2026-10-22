-- Dastakhat - append only protection of the audit trail.
--
-- The hash chain makes tampering visible, but a privileged database administrator could still
-- rewrite the whole history. To make that harder the application role is not allowed to
-- update or delete rows of the audit table.
--
-- The role is created by the deployment (docker compose) and is only granted INSERT and
-- SELECT on audit_events. The trigger below is the second line of defence: even the owner of
-- the table cannot change an existing row without raising an exception.

CREATE OR REPLACE FUNCTION dastakhat_audit_events_append_only()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is append only: % is not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_audit_events_append_only ON audit_events;

CREATE TRIGGER trg_audit_events_append_only
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION dastakhat_audit_events_append_only();
