-- Signing out everywhere, or a removed person: all their tokens end at once.
DELETE FROM doorman.tokens WHERE token__person_id = :token/person-id
