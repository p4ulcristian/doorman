-- A role of one scope: its id, its scope, its name (unique in the scope,
-- kept so by groundwork_access.memberships under lock_roles_of_scope.sql).
INSERT INTO groundwork_access.roles (role__id, role__scope_kind, role__scope_id, role__name)
VALUES (:role/id, :role/scope-kind, :role/scope-id, :role/name)
