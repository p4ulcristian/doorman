-- groundwork_access.find_asking_person: The person asking, from groundwork_access.asking (set_asking.sql); null for none.
-- Declared to groundwork-postgres by groundwork_access.api/function-files.
CREATE OR REPLACE FUNCTION groundwork_access.find_asking_person() RETURNS uuid
LANGUAGE sql STABLE AS $$
  SELECT (NULLIF(current_setting('groundwork_access.asking', true), '')::jsonb ->> 'person')::uuid
$$
