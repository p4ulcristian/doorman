-- Reading past the gates, for this transaction only: explain-refusal's
-- read-only look at a row the asker may not see.
SELECT set_config('doorman.passing', 'on', true) AS passing__set
