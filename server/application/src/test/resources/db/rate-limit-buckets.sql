-- Bucket4j owns this table outside Hibernate; production creates it through Liquibase.
CREATE TABLE IF NOT EXISTS auth_rate_limit_bucket (
    id varchar(255) PRIMARY KEY,
    state bytea,
    expires_at bigint
);
CREATE INDEX IF NOT EXISTS ix_auth_rate_limit_bucket_expires_at ON auth_rate_limit_bucket (expires_at);
