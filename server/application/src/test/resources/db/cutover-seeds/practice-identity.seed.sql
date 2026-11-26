-- Practice versions of the previous release have no identity: two versions of a practice adopted from the catalog,
-- one of the workspace's own practice, and one of a practice whose catalog entry an instance moved to another kind.
-- The seed runs on every release that an upgrade can start from. A release that already has the conversion writes the
-- identity with each version, so the seed then fills it the same way. If a later release makes the columns NOT NULL,
-- this seed must write them in its INSERT or be removed.
BEGIN;
SET CONSTRAINTS ALL DEFERRED;
INSERT INTO workspace(id,slug,display_name,account_login,account_type,status)
VALUES(979001,'identity-cutover','Identity cutover','identity-cutover','ORG','ACTIVE');
INSERT INTO account(id,display_name,status) VALUES(979011,'Practice author','ACTIVE');
INSERT INTO practice(id,workspace_id,slug,kind,current_version_id,catalog_slug,catalog_base)
VALUES(979002,979001,'scope-one-reviewable-change','scm.pull_request',979003,'scope-one-reviewable-change',
       '{"kind":"scm.pull_request","groupSlug":null,"definition":{"name":"Cutover practice"}}'),
      (979004,979001,'own-practice','scm.pull_request',979005,NULL,NULL),
      (979007,979001,'describe-what-and-why','scm.issue',979008,'describe-what-and-why',
       '{"kind":"scm.issue","groupSlug":null,"definition":{"name":"Cutover practice"}}');
INSERT INTO practice_version(id,workspace_id,practice_id,number,created_by_account_id,definition)
SELECT v.id,979001,v.practice_id,v.number,v.creator,
 '{"name":"Cutover practice","criteria":"Explain the purpose.","about":"AUTHOR",
   "reviewOn":{"events":["scm.pull_request.opened"],"state":{}},"appliesWhen":null,
   "reads":{"contract":"3.0.0","sources":[{"source":"scm.pull-request.core","need":"REQUIRED"}]},
   "reviewedBy":{"by":"HEPHAESTUS","humanReason":null,"limits":[]},"script":null,"scan":null,
   "lineNotes":false,"guidance":{"whyItMatters":null,"whatGoodLooksLike":null,"goingWell":null,"visual":null,"guide":null}}'
FROM (VALUES (979003,979002,1,979011),(979010,979002,2,NULL),(979005,979004,1,NULL),(979008,979007,1,NULL))
    AS v(id,practice_id,number,creator);
DO $$
BEGIN
    IF EXISTS (SELECT FROM information_schema.columns
               WHERE table_schema = 'public' AND table_name = 'practice_version' AND column_name = 'identity_key') THEN
        UPDATE practice_version
        SET identity_key = CASE practice_id
                WHEN 979002 THEN 'software-engineering/scope-one-reviewable-change'
                ELSE 'local/979001/' || practice_id END,
            comparability_class = review_digest
        WHERE workspace_id = 979001 AND identity_key IS NULL;
    END IF;
END
$$;
COMMIT;
