-- doorman.is_passing: Whether one of doorman's own functions is reading past the gates now (doorman.passing 'on').
-- Declared to cellar by doorman.api/function-files.
CREATE OR REPLACE FUNCTION doorman.is_passing() RETURNS boolean
LANGUAGE sql STABLE AS $$
  SELECT coalesce(current_setting('doorman.passing', true), '') = 'on'
$$
