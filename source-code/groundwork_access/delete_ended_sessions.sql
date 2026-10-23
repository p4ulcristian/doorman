-- Sessions that ended, forgotten.
DELETE FROM groundwork_access.sessions WHERE session__ends_at < now()
