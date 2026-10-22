-- A session: the cookie's fingerprint, never the cookie itself.
INSERT INTO doorman.sessions (session__fingerprint, session__person_id, session__ends_at)
VALUES (:session/fingerprint, :session/person-id, :session/ends-at)
