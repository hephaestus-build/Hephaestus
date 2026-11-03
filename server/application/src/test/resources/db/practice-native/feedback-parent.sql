BEGIN;
INSERT INTO account(id,display_name,status) VALUES(978007,'Other developer','ACTIVE');
INSERT INTO membership(workspace_id,account_id,role) VALUES(978001,978007,'MEMBER');
INSERT INTO result(id,workspace_id,review_id,practice_id,practice_version_id,item_id,item_version,developer_id,outcome,summary,coverage,recorded_at)
VALUES('00000000-0000-0000-0000-000000978021',978001,'00000000-0000-0000-0000-000000978011',978004,978005,978006,
 'native-v1',978003,'MET','The change explains its purpose.',
 '{"source":"host","complete":true,"reads":1,"truncatedReads":0,"failedReads":0,"receipts":[]}',now());
INSERT INTO feedback(id,workspace_id,review_id,item_id,developer_id,destination,state,body,created_at,delivered_at)
VALUES('00000000-0000-0000-0000-000000978041',978001,'00000000-0000-0000-0000-000000978011',978006,978003,
 'ON_WORK','DELIVERED','Explain the purpose.',now(),now()),
 ('00000000-0000-0000-0000-000000978044',978001,'00000000-0000-0000-0000-000000978011',978006,978003,
 'CONVERSATION','READY','Prepared brief.',now(),NULL);
INSERT INTO feedback_basis(id,workspace_id,feedback_id,result_id,developer_id,ordinal)
VALUES('00000000-0000-0000-0000-000000978043',978001,'00000000-0000-0000-0000-000000978041','00000000-0000-0000-0000-000000978021',978003,0),
 ('00000000-0000-0000-0000-000000978046',978001,'00000000-0000-0000-0000-000000978044','00000000-0000-0000-0000-000000978021',978003,0);
INSERT INTO feedback_copy(id,workspace_id,feedback_id,kind,position,state,external_ref,write_started_at,posted_at,write_key)
VALUES('00000000-0000-0000-0000-000000978042',978001,'00000000-0000-0000-0000-000000978041','SUMMARY',0,
 'POSTED','provider:native',now(),now(),'00000000-0000-0000-0000-000000978042'),
 ('00000000-0000-0000-0000-000000978045',978001,'00000000-0000-0000-0000-000000978044','CHAT_TURN',0,
 'PLANNED',NULL,NULL,NULL,'00000000-0000-0000-0000-000000978045');
INSERT INTO mentor_conversation(id,workspace_id,account_id,created_at,last_activity_at)
VALUES('00000000-0000-0000-0000-000000978051',978001,978003,'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z');
INSERT INTO mentor_turn(id,workspace_id,conversation_id,request_id,status,surface,attempt,started_at,created_at)
VALUES('00000000-0000-0000-0000-000000978053',978001,'00000000-0000-0000-0000-000000978051','native-turn',
 'RUNNING','APP',1,'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z');
INSERT INTO mentor_entry(id,workspace_id,conversation_id,seq,kind,surface,text,payload,turn_id,created_at)
VALUES('00000000-0000-0000-0000-000000978052',978001,'00000000-0000-0000-0000-000000978051',1,'HEPH','APP',
 'Actual shown words.',
 '{"type":"message","id":"native-answer","message":{"role":"assistant","content":[{"type":"text","text":"Actual shown words."}]}}',
 '00000000-0000-0000-0000-000000978053','2026-01-01T00:00:00Z');
COMMIT;
