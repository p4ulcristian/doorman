-- doorman.find_asking_person: The person asking, from doorman.asking (set_asking.sql); null for none.
-- Declared to cellar by doorman.api/function-files.
CREATE OR REPLACE FUNCTION doorman.find_asking_person() RETURNS uuid
LANGUAGE sql STABLE AS $$
  SELECT (NULLIF(current_setting('doorman.asking', true), '')::jsonb ->> 'person')::uuid
$$
