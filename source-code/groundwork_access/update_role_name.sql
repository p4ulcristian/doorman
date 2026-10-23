-- A role renamed: one row; memberships name it by its id.
UPDATE groundwork_access.roles SET role__name = :role/name WHERE role__id = :role/id
