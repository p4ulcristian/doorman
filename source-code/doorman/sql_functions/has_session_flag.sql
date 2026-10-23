-- doorman.has_session_flag: Whether the asker's session has a flag ("impersonated", …), from doorman.asking's "flags".
-- Declared to cellar by doorman.api/function-files.
CREATE OR REPLACE FUNCTION doorman.has_session_flag(flag text) RETURNS boolean
LANGUAGE sql STABLE AS $$
  SELECT coalesce((NULLIF(current_setting('doorman.asking', true), '')::jsonb -> 'flags') @> to_jsonb(flag), false)
$$
