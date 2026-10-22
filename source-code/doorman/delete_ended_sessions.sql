-- Sessions that ended, forgotten.
DELETE FROM doorman.sessions WHERE session__ends_at < now()
