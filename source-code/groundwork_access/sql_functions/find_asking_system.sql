-- groundwork_access.find_asking_system: The named system actor running (run-as-system!), from groundwork_access.asking; null for none.
-- Declared to groundwork-postgres by groundwork_access.api/function-files.
CREATE OR REPLACE FUNCTION groundwork_access.find_asking_system() RETURNS text
LANGUAGE sql STABLE AS $$
  SELECT NULLIF(current_setting('groundwork_access.asking', true), '')::jsonb ->> 'system'
$$
