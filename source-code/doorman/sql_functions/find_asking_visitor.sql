-- doorman.find_asking_visitor: The visitor asking, a visitor session's id, from doorman.asking; null for none.
-- Declared to cellar by doorman.api/function-files.
CREATE OR REPLACE FUNCTION doorman.find_asking_visitor() RETURNS uuid
LANGUAGE sql STABLE AS $$
  SELECT (NULLIF(current_setting('doorman.asking', true), '')::jsonb ->> 'visitor')::uuid
$$
