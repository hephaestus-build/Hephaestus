-- auth_event.id is allocated by AuthEventSequence, not by Hibernate. The test profile runs
-- this after Hibernate creates the schema so every context can persist its real audit events.
CREATE SEQUENCE IF NOT EXISTS auth_event_id_seq;
