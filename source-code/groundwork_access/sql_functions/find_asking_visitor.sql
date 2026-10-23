-- groundwork_access.find_asking_visitor: The visitor asking, a visitor session's id, from groundwork_access.asking; null for none.
-- Declared to groundwork-postgres by groundwork_access.api/function-files.
CREATE OR REPLACE FUNCTION groundwork_access.find_asking_visitor() RETURNS uuid
LANGUAGE sql STABLE AS $$
  SELECT (NULLIF(current_setting('groundwork_access.asking', true), '')::jsonb ->> 'visitor')::uuid
$$
