# A reporter failure must neither stop a backup nor cause Kubernetes to repeat it.
set -eu

if [ "$1" = --inspect ]; then
    if info=$(pgbackrest --stanza=hephaestus --output=json --log-level-console=off info); then
        printf '%s' "$info" | perl "$(dirname "$0")/backup-oldest-point.pl" || true
    fi
    exit 0
fi

status=0
pgbackrest --stanza=hephaestus --type="$1" backup || status=$?
export HEPH_BACKUP_REPORTER="$0"

# Hold the same lock through inspection and publication so one Job cannot publish an older observation
# after another Job, and a sweep cannot delete against the horizon while it changes.
if ! timeout --kill-after=5s 60s psql --no-psqlrc --quiet --no-align --tuples-only --set ON_ERROR_STOP=1 <<'SQL'
SET lock_timeout = '5s';
SELECT pg_advisory_lock(hashtext('hephaestus'), hashtext('privacy-journal-backup-horizon')) IS NOT NULL AS locked \gset
\set oldest `sh "$HEPH_BACKUP_REPORTER" --inspect`
WITH report AS (
    SELECT clock_timestamp() AS observed_at, to_timestamp(NULLIF(:'oldest', '')::double precision) AS oldest_at
)
INSERT INTO public.privacy_journal_backup_horizon (id, observed_at, oldest_backup_at)
SELECT 1, observed_at, CASE WHEN oldest_at <= observed_at THEN oldest_at END FROM report
ON CONFLICT (id) DO UPDATE
SET observed_at = excluded.observed_at, oldest_backup_at = excluded.oldest_backup_at
RETURNING oldest_backup_at IS NOT NULL AS verified \gset
\if :verified
\else
\warn Cannot verify the oldest PostgreSQL recovery point. Journal pruning will wait.
\endif
SQL
then
    printf '%s\n' "Cannot publish the PostgreSQL recovery point. Check the backup reporter login and horizon alert." >&2
fi
exit "$status"
