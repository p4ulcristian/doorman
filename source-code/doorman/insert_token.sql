-- A token a person made: its fingerprint, never its text; the characters
-- its list shows; its name and end.
INSERT INTO doorman.tokens (token__id, token__fingerprint, token__person_id, token__name, token__shown_start, token__ends_at)
VALUES (:token/id, :token/fingerprint, :token/person-id, :token/name, :token/shown-start, :token/ends-at)
