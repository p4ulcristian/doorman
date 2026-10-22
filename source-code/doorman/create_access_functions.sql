-- Who is asking, as the gates and the check read it: one setting per
-- transaction, doorman.asking, set by set_asking.sql:
--   {"person": "uuid or null", "holding": {"shop orders/see": ["scope id", …]}}
CREATE SCHEMA IF NOT EXISTS doorman;

CREATE OR REPLACE FUNCTION doorman.find_asking_person() RETURNS uuid
LANGUAGE sql STABLE AS $$
  SELECT (NULLIF(current_setting('doorman.asking', true), '')::jsonb ->> 'person')::uuid
$$;

CREATE OR REPLACE FUNCTION doorman.find_scopes_holding(kind text, action text) RETURNS uuid[]
LANGUAGE sql STABLE AS $$
  SELECT coalesce(array(SELECT jsonb_array_elements_text(
                          NULLIF(current_setting('doorman.asking', true), '')::jsonb -> 'holding' -> (kind || ' ' || action)
                        )::uuid),
                  '{}')
$$
