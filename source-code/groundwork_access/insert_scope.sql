-- A scope: the app's own row (a shop), known to access by its kind and id,
-- under its parent scope (a company) or none.
INSERT INTO groundwork_access.scopes (scope__kind, scope__id, scope__parent_kind, scope__parent_id)
VALUES (:scope/kind, :scope/id, :scope/parent-kind, :scope/parent-id)
