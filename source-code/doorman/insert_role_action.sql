-- One action a role of one scope allows.
INSERT INTO doorman.role_actions (role_action__scope_kind, role_action__scope_id, role_action__role, role_action__action)
VALUES (:role-action/scope-kind, :role-action/scope-id, :role-action/role, :role-action/action)
