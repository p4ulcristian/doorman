-- A person in a scope; the roles they hold there are rows of
-- groundwork_access.membership_roles, none for no role yet.
INSERT INTO groundwork_access.memberships (membership__person_id, membership__scope_kind, membership__scope_id)
VALUES (:membership/person-id, :membership/scope-kind, :membership/scope-id)
