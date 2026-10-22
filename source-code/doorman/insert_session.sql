-- A session: the cookie's fingerprint, never the cookie itself. A person's
-- has its person (and its flags); a visitor's, the visitor's id.
INSERT INTO doorman.sessions (session__fingerprint, session__person_id, session__visitor_id, session__flags, session__ends_at)
VALUES (:session/fingerprint, :session/person-id, :session/visitor-id, :session/flags, :session/ends-at)
