-- A role gone; its actions went first, and nobody holds it.
DELETE FROM doorman.roles WHERE role__id = :role/id
