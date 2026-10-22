-- A session seen again lasts longer.
UPDATE doorman.sessions SET session__ends_at = :session/ends-at
WHERE session__fingerprint = :session/fingerprint
