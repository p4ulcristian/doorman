-- groundwork_access.is_acting: Whether an action counts now: the one the handler acts as, or every action ("every_action").
-- Declared to groundwork-postgres by groundwork_access.api/function-files.
CREATE OR REPLACE FUNCTION groundwork_access.is_acting(action text) RETURNS boolean
LANGUAGE sql STABLE AS $$
  SELECT coalesce((NULLIF(current_setting('groundwork_access.asking', true), '')::jsonb ->> 'every_action')::boolean, false)
      OR coalesce(NULLIF(current_setting('groundwork_access.asking', true), '')::jsonb ->> 'acting' = action, false)
$$
