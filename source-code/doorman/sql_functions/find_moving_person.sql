-- doorman.find_moving_person: The person sign-in is moving a visitor's rows to, from doorman.asking's "moving"; null otherwise.
-- Declared to cellar by doorman.api/function-files.
CREATE OR REPLACE FUNCTION doorman.find_moving_person() RETURNS uuid
LANGUAGE sql STABLE AS $$
  SELECT (NULLIF(current_setting('doorman.asking', true), '')::jsonb -> 'moving' ->> 'person')::uuid
$$
