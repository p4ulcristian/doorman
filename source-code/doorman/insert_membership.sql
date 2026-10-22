-- A person in a scope, with one role there.
INSERT INTO doorman.memberships (membership__person_id, membership__scope_kind, membership__scope_id, membership__role)
VALUES (:membership/person-id, :membership/scope-kind, :membership/scope-id, :membership/role)
