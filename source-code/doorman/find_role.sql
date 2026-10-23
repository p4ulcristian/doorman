-- A role, if it is this scope's.
SELECT role__id, role__name FROM doorman.roles
WHERE role__id = :role/id AND role__scope_kind = :role/scope-kind AND role__scope_id = :role/scope-id
