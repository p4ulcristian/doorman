-- Tokens that ended, forgotten.
DELETE FROM doorman.tokens WHERE token__ends_at < now()
