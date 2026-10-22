-- A scope and every scope above it, nearest first, with the person's role
-- in each (nil where they aren't a member).
WITH RECURSIVE up (kind, id, depth) AS (
  SELECT CAST(:scope/kind AS text), CAST(:scope/id AS uuid), 0
  UNION
  SELECT s.scope__parent_kind, s.scope__parent_id, u.depth + 1
  FROM doorman.scopes s JOIN up u ON s.scope__kind = u.kind AND s.scope__id = u.id
  WHERE s.scope__parent_id IS NOT NULL
)
SELECT u.kind AS scope__kind, u.id AS scope__id, u.depth AS scope__depth, m.membership__role AS membership__role
FROM up u
LEFT JOIN doorman.memberships m ON m.membership__scope_kind = u.kind AND m.membership__scope_id = u.id
                               AND m.membership__person_id = :membership/person-id
ORDER BY u.depth
