-- doorman.is_acting: Whether an action counts now: the one the handler acts as, or every action ("every_action").
-- Declared to cellar by doorman.api/function-files.
CREATE OR REPLACE FUNCTION doorman.is_acting(action text) RETURNS boolean
LANGUAGE sql STABLE AS $$
  SELECT coalesce((NULLIF(current_setting('doorman.asking', true), '')::jsonb ->> 'every_action')::boolean, false)
      OR coalesce(NULLIF(current_setting('doorman.asking', true), '')::jsonb ->> 'acting' = action, false)
$$
