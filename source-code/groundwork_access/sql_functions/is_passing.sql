-- groundwork_access.is_passing: Whether one of groundwork-access's own functions is reading past the gates now (groundwork_access.passing 'on').
-- Declared to groundwork-postgres by groundwork_access.api/function-files.
CREATE OR REPLACE FUNCTION groundwork_access.is_passing() RETURNS boolean
LANGUAGE sql STABLE AS $$
  SELECT coalesce(current_setting('groundwork_access.passing', true), '') = 'on'
$$
