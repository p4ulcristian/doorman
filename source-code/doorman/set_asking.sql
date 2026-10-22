-- Who is asking, for this transaction only.
SELECT set_config('doorman.asking', :asking, true) AS asking_set
