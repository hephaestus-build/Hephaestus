BEGIN;
INSERT INTO workspace(id,slug,display_name,account_login,account_type,status)
VALUES(978001,'native-results','Native results','native-results','ORG','ACTIVE'),
      (978002,'foreign-results','Foreign results','foreign-results','ORG','ACTIVE');
INSERT INTO account(id,display_name,status) VALUES(978003,'Developer','ACTIVE');
INSERT INTO membership(workspace_id,account_id,role) VALUES(978001,978003,'MEMBER');
INSERT INTO item(id,workspace_id,kind,source_id)
VALUES(978006,978001,'scm.pull_request',42);
INSERT INTO practice(id,workspace_id,slug,kind,current_version_id,feedback_mode)
VALUES(978004,978001,'native-practice','scm.pull_request',978005,'AUTOMATIC');
INSERT INTO practice_version(id,workspace_id,practice_id,number,definition)
VALUES(978005,978001,978004,1,
 '{"name":"Native practice","criteria":"Explain the purpose.","about":"AUTHOR",
   "reviewOn":{"events":["scm.pull_request.opened"],"state":{}},"appliesWhen":null,
   "reads":{"contract":"3.0.0","sources":[{"source":"scm.pull-request.core","need":"COMPLETE"}]},
   "reviewedBy":{"by":"HEPHAESTUS","humanReason":null,"limits":[]},"script":null,"scan":null,
   "lineNotes":false,"guidance":{"whyItMatters":null,"whatGoodLooksLike":null,"goingWell":null,"visual":null,"guide":null}}');
INSERT INTO review(id,workspace_id,item_id,kind,item_version,origin,status,ordered_at)
VALUES('00000000-0000-0000-0000-000000978011',978001,978006,'ITEM','native-v1','REQUEST','QUEUED',now());
INSERT INTO review_practice(review_id,workspace_id,practice_id,practice_version_id,status)
VALUES('00000000-0000-0000-0000-000000978011',978001,978004,978005,'READY');
INSERT INTO review_item(review_id,workspace_id,practice_id,item_id,item_version)
VALUES('00000000-0000-0000-0000-000000978011',978001,978004,978006,'native-v1');
COMMIT;
