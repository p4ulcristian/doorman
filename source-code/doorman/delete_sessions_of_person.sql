-- Signing out everywhere, or a removed person: all their sessions end at once.
DELETE FROM doorman.sessions WHERE session__person_id = :session/person-id
