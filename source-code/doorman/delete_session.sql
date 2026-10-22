-- Signing out: this session ends at once.
DELETE FROM doorman.sessions WHERE session__fingerprint = :session/fingerprint
