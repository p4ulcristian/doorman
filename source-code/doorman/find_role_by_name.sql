-- The role of a scope that has this name, if there is one.
SELECT role__id FROM doorman.roles
WHERE role__scope_kind = :role/scope-kind AND role__scope_id = :role/scope-id AND role__name = :role/name
