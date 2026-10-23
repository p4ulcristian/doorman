-- A scope, if there is one of this kind and id.
SELECT scope__kind, scope__id FROM groundwork_access.scopes
WHERE scope__kind = :scope/kind AND scope__id = :scope/id
