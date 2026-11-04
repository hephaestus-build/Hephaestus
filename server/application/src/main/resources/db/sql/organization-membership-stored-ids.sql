-- Earlier GitHub member webhooks stored memberships under the GitHub organization id instead of the stored one.
-- A row is remapped only when no stored organization has its id and exactly one has it as its GitHub id.
-- Where the full sync already wrote the same membership under the stored id, the row of the sync stays.
DELETE FROM organization_membership m
USING organization o
WHERE o.native_id = m.organization_id
  AND NOT EXISTS (SELECT 1 FROM organization i WHERE i.id = m.organization_id)
  AND (SELECT count(*) FROM organization n WHERE n.native_id = m.organization_id) = 1
  AND EXISTS (SELECT 1 FROM organization_membership s WHERE s.organization_id = o.id AND s.user_id = m.user_id);

UPDATE organization_membership m
SET organization_id = o.id
FROM organization o
WHERE o.native_id = m.organization_id
  AND NOT EXISTS (SELECT 1 FROM organization i WHERE i.id = m.organization_id)
  AND (SELECT count(*) FROM organization n WHERE n.native_id = m.organization_id) = 1;

DELETE FROM organization_membership m
WHERE NOT EXISTS (SELECT 1 FROM organization o WHERE o.id = m.organization_id)
   OR NOT EXISTS (SELECT 1 FROM "user" u WHERE u.id = m.user_id);
