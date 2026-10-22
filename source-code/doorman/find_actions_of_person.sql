-- Every scope a person is in, their role there, and the actions it allows
-- (none listed for Owner, who holds every action of the kind).
SELECT m.membership__scope_kind AS membership__scope_kind,
       m.membership__scope_id   AS membership__scope_id,
       m.membership__role       AS membership__role,
       ra.role_action__action   AS role_action__action
FROM doorman.memberships m
LEFT JOIN doorman.role_actions ra
       ON ra.role_action__scope_kind = m.membership__scope_kind
      AND ra.role_action__scope_id   = m.membership__scope_id
      AND ra.role_action__role       = m.membership__role
WHERE m.membership__person_id = :membership/person-id
