-- Every action a role allows, taken away.
DELETE FROM groundwork_access.role_actions WHERE role_action__role_id = :role-action/role-id
