-- doorman.find_moving_visitor: The visitor whose rows sign-in is moving now, from doorman.asking's "moving"; null otherwise.
-- Declared to cellar by doorman.api/function-files.
CREATE OR REPLACE FUNCTION doorman.find_moving_visitor() RETURNS uuid
LANGUAGE sql STABLE AS $$
  SELECT (NULLIF(current_setting('doorman.asking', true), '')::jsonb -> 'moving' ->> 'visitor')::uuid
$$
