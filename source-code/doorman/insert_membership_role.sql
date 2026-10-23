-- One role a member holds in the scope.
INSERT INTO doorman.membership_roles (membership_role__person_id, membership_role__scope_kind, membership_role__scope_id, membership_role__role_id)
VALUES (:membership-role/person-id, :membership-role/scope-kind, :membership-role/scope-id, :membership-role/role-id)
