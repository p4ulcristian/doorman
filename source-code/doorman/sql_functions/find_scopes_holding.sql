-- doorman.find_scopes_holding: The ids of the scopes of a kind where the handler acts holding an action, from doorman.asking's "holding".
-- Declared to cellar by doorman.api/function-files.
CREATE OR REPLACE FUNCTION doorman.find_scopes_holding(kind text, action text) RETURNS uuid[]
LANGUAGE sql STABLE AS $$
  SELECT coalesce(array(SELECT jsonb_array_elements_text(
                          NULLIF(current_setting('doorman.asking', true), '')::jsonb -> 'holding' -> (kind || ' ' || action)
                        )::uuid),
                  '{}')
$$
