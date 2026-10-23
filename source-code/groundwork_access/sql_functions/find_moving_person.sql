-- groundwork_access.find_moving_person: The person sign-in is moving a visitor's rows to, from groundwork_access.asking's "moving"; null otherwise.
-- Declared to groundwork-postgres by groundwork_access.api/function-files.
CREATE OR REPLACE FUNCTION groundwork_access.find_moving_person() RETURNS uuid
LANGUAGE sql STABLE AS $$
  SELECT (NULLIF(current_setting('groundwork_access.asking', true), '')::jsonb -> 'moving' ->> 'person')::uuid
$$
