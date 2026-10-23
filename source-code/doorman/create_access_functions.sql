-- Who is asking, as the gates and the check read it: one setting per
-- transaction, doorman.asking, set by set_asking.sql:
--   {"person": "uuid or null", "visitor": "uuid or null", "flags": ["impersonated", …],
--    "acting": "orders/edit" or null, "every_action": false,
--    "holding": {"shop orders/edit": ["scope id", …]},
--    "moving": {"visitor": "uuid", "person": "uuid"}}       (only while sign-in moves a visitor's rows)
--   {"system": "postbox"}                                   (only while a named system actor runs, run-as-system!)
-- "acting" is the one action a handler's steps act as: a person's sentences
-- count only for it (doorman.is_acting), and "holding" lists only the
-- scopes the handler acts in. "every_action" true: every action counts
-- (run-as-asker!, "what may I do?", explain-refusal, the check itself, a
-- named system actor's :anyone rows).
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

CREATE OR REPLACE FUNCTION doorman.find_asking_system() RETURNS text
LANGUAGE sql STABLE AS $$
  SELECT NULLIF(current_setting('doorman.asking', true), '')::jsonb ->> 'system'
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
$$;

CREATE OR REPLACE FUNCTION doorman.is_acting(action text) RETURNS boolean
LANGUAGE sql STABLE AS $$
  SELECT coalesce((NULLIF(current_setting('doorman.asking', true), '')::jsonb ->> 'every_action')::boolean, false)
      OR coalesce(NULLIF(current_setting('doorman.asking', true), '')::jsonb ->> 'acting' = action, false)
$$
