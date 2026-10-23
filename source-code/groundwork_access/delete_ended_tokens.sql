-- Tokens that ended, forgotten.
DELETE FROM groundwork_access.tokens WHERE token__ends_at < now()
