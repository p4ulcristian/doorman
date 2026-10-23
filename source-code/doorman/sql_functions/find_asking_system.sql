-- doorman.find_asking_system: The named system actor running (run-as-system!), from doorman.asking; null for none.
-- Declared to cellar by doorman.api/function-files.
CREATE OR REPLACE FUNCTION doorman.find_asking_system() RETURNS text
LANGUAGE sql STABLE AS $$
  SELECT NULLIF(current_setting('doorman.asking', true), '')::jsonb ->> 'system'
$$
