-- A person's membership of a scope, if they are in it.
SELECT membership__person_id FROM doorman.memberships
WHERE membership__person_id = :membership/person-id
  AND membership__scope_kind = :membership/scope-kind AND membership__scope_id = :membership/scope-id
