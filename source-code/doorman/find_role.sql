-- A role of a scope, if the scope has it.
SELECT role__name FROM doorman.roles
WHERE role__scope_kind = :role/scope-kind AND role__scope_id = :role/scope-id AND role__name = :role/name
