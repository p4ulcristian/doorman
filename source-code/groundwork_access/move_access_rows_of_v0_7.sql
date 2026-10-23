-- groundwork-access's access rows of v0.7, in the tables the first step of the move
-- renamed, copied into today's: each role gets an id; each membership's
-- one role name becomes the set holding that role's id. Run once, by
-- groundwork-postgres, as a change; the old tables are dropped after it in the same
-- transaction.
INSERT INTO groundwork_access.roles (role__id, role__scope_kind, role__scope_id, role__name)
SELECT gen_random_uuid(), role__scope_kind, role__scope_id, role__name FROM groundwork_access.roles_of_v0_7;

INSERT INTO groundwork_access.role_actions (role_action__role_id, role_action__action)
SELECT r.role__id, o.role_action__action
FROM groundwork_access.role_actions_of_v0_7 o
JOIN groundwork_access.roles r ON r.role__scope_kind = o.role_action__scope_kind
                    AND r.role__scope_id   = o.role_action__scope_id
                    AND r.role__name       = o.role_action__role;

INSERT INTO groundwork_access.memberships (membership__person_id, membership__scope_kind, membership__scope_id)
SELECT membership__person_id, membership__scope_kind, membership__scope_id FROM groundwork_access.memberships_of_v0_7;

INSERT INTO groundwork_access.membership_roles (membership_role__person_id, membership_role__scope_kind, membership_role__scope_id, membership_role__role_id)
SELECT m.membership__person_id, m.membership__scope_kind, m.membership__scope_id, r.role__id
FROM groundwork_access.memberships_of_v0_7 m
JOIN groundwork_access.roles r ON r.role__scope_kind = m.membership__scope_kind
                    AND r.role__scope_id   = m.membership__scope_id
                    AND r.role__name       = m.membership__role
