-- The session of a fingerprint, if it hasn't ended.
SELECT session__person_id, session__ends_at FROM doorman.sessions
WHERE session__fingerprint = :session/fingerprint AND session__ends_at > now()
