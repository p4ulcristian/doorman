-- groundwork_access.has_session_flag: Whether the asker's session has a flag ("impersonated", …), from groundwork_access.asking's "flags".
-- Declared to groundwork-postgres by groundwork_access.api/function-files.
CREATE OR REPLACE FUNCTION groundwork_access.has_session_flag(flag text) RETURNS boolean
LANGUAGE sql STABLE AS $$
  SELECT coalesce((NULLIF(current_setting('groundwork_access.asking', true), '')::jsonb -> 'flags') @> to_jsonb(flag), false)
$$
