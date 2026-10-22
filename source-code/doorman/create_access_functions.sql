-- Who is asking, as the gates and the check read it: one setting per
-- transaction, doorman.asking, set by set_asking.sql:
--   {"person": "uuid or null", "visitor": "uuid or null", "flags": ["impersonated", …],
--    "holding": {"shop orders/see": ["scope id", …]},
--    "moving": {"visitor": "uuid", "person": "uuid"}}       (only while sign-in moves a visitor's rows)
-- and doorman.passing, 'on' only while one of doorman's own functions reads
-- past the gates (a :via's related rows, a scope's state, a changed row).
CREATE SCHEMA IF NOT EXISTS doorman;

CREATE OR REPLACE FUNCTION doorman.find_asking_person() RETURNS uuid
LANGUAGE sql STABLE AS $$
  SELECT (NULLIF(current_setting('doorman.asking', true), '')::jsonb ->> 'person')::uuid
$$;

CREATE OR REPLACE FUNCTION doorman.find_asking_visitor() RETURNS uuid
LANGUAGE sql STABLE AS $$
  SELECT (NULLIF(current_setting('doorman.asking', true), '')::jsonb ->> 'visitor')::uuid
$$;

CREATE OR REPLACE FUNCTION doorman.find_scopes_holding(kind text, action text) RETURNS uuid[]
LANGUAGE sql STABLE AS $$
  SELECT coalesce(array(SELECT jsonb_array_elements_text(
                          NULLIF(current_setting('doorman.asking', true), '')::jsonb -> 'holding' -> (kind || ' ' || action)
                        )::uuid),
                  '{}')
$$;

CREATE OR REPLACE FUNCTION doorman.has_session_flag(flag text) RETURNS boolean
LANGUAGE sql STABLE AS $$
  SELECT coalesce((NULLIF(current_setting('doorman.asking', true), '')::jsonb -> 'flags') @> to_jsonb(flag), false)
$$;

CREATE OR REPLACE FUNCTION doorman.is_passing() RETURNS boolean
LANGUAGE sql STABLE AS $$
  SELECT coalesce(current_setting('doorman.passing', true), '') = 'on'
$$;

CREATE OR REPLACE FUNCTION doorman.find_moving_visitor() RETURNS uuid
LANGUAGE sql STABLE AS $$
  SELECT (NULLIF(current_setting('doorman.asking', true), '')::jsonb -> 'moving' ->> 'visitor')::uuid
$$;

CREATE OR REPLACE FUNCTION doorman.find_moving_person() RETURNS uuid
LANGUAGE sql STABLE AS $$
  SELECT (NULLIF(current_setting('doorman.asking', true), '')::jsonb -> 'moving' ->> 'person')::uuid
$$
