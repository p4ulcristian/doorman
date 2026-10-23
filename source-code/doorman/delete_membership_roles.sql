-- Every role a member holds in a scope, taken away.
DELETE FROM doorman.membership_roles
WHERE membership_role__person_id = :membership-role/person-id
  AND membership_role__scope_kind = :membership-role/scope-kind AND membership_role__scope_id = :membership-role/scope-id
