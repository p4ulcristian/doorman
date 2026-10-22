-- A person leaves a scope.
DELETE FROM doorman.memberships
WHERE membership__person_id = :membership/person-id
  AND membership__scope_kind = :membership/scope-kind AND membership__scope_id = :membership/scope-id
