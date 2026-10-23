-- groundwork_access.find_moving_visitor: The visitor whose rows sign-in is moving now, from groundwork_access.asking's "moving"; null otherwise.
-- Declared to groundwork-postgres by groundwork_access.api/function-files.
CREATE OR REPLACE FUNCTION groundwork_access.find_moving_visitor() RETURNS uuid
LANGUAGE sql STABLE AS $$
  SELECT (NULLIF(current_setting('groundwork_access.asking', true), '')::jsonb -> 'moving' ->> 'visitor')::uuid
$$
