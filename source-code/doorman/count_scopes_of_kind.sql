-- How many scopes a kind has.
SELECT count(*) AS scopes__count FROM doorman.scopes WHERE scope__kind = :scope/kind
