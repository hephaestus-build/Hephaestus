-- Hibernate cannot create ShedLock's table because it has no JPA entity.
-- Migrated-schema tests use Liquibase instead of this fixture.
CREATE TABLE IF NOT EXISTS shedlock (
    name VARCHAR(64) NOT NULL,
    lock_until TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    locked_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    locked_by VARCHAR(255) NOT NULL,
    CONSTRAINT pk_shedlock PRIMARY KEY (name)
);
