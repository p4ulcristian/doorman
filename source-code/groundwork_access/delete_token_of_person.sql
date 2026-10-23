-- Revoking: this token of this person ends at once.
DELETE FROM groundwork_access.tokens WHERE token__id = :token/id AND token__person_id = :token/person-id
