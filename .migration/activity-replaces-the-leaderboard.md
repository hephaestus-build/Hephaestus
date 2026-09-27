#### 🔴 Leaderboard, leagues and XP retired; Activity replaces them

The leaderboard, leagues and league points, experience points and levels, the league reset, and the
weekly Slack leaderboard digest are removed. So are the workspace switches **Leaderboard**, **XP and
level progression** and **Leagues**, the digest's day, time, channel and team settings, and the Slack
connection card's test message. Every workspace now has **Activity** (the workspace home) and
**Workspace activity**: counts and lists of pull or merge requests, reviews, issues and comments, with
members listed by name — see [Activity](https://docs.hephaestus.build/user/activity).

**League, XP and leaderboard data is deleted by the upgrade.** The upgrade first attributes each recorded
merge to the pull request's author (a merge whose author is unknown is no longer counted for anyone) and
turns Heph off in workspaces that had it switched off, then drops these columns with their data:

- `workspace`: `mentor_enabled`, `leaderboard_enabled`, `progression_enabled`, `leagues_enabled`,
  `leaderboard_schedule_day`, `leaderboard_schedule_time`, `leaderboard_notification_enabled`,
  `leaderboard_league_cycle_at`
- `workspace_membership`: `league_points`
- `activity_event`: `xp`, with its check constraint and the leaderboard index

Nothing in the new release reads them, and neither the upgrade nor a rollback can bring the values back: a
rollback recreates the columns empty, with every switch off (Heph included) and every league point and XP
value at 0. **Before you upgrade,** stop the application, take the regular database backup
([Backup and restore](https://docs.hephaestus.build/admin/backup-restore)) — restoring it is the only way
back to the previous release with its data — and, if you want to keep the league and XP history outside
that backup, export it from the self-host directory:

```bash
(
set -eu
umask 077
cd /opt/hephaestus/docker/self-host
dc() { docker compose --env-file .env --env-file release-lock.env "$@"; }
snapshot_dir=$(mktemp -d "/var/tmp/hephaestus-leaderboard-snapshot-$(date -u +%Y%m%dT%H%M%SZ)-XXXXXX")
dc exec -T postgres psql -U root -d hephaestus -v ON_ERROR_STOP=1 -c "\copy (SELECT id, slug, mentor_enabled, leaderboard_enabled, progression_enabled, leagues_enabled, leaderboard_schedule_day, leaderboard_schedule_time, leaderboard_notification_enabled, leaderboard_league_cycle_at FROM workspace ORDER BY id) TO STDOUT WITH CSV HEADER" > "$snapshot_dir/workspace.csv"
dc exec -T postgres psql -U root -d hephaestus -v ON_ERROR_STOP=1 -c "\copy (SELECT m.workspace_id, m.user_id, u.login, m.league_points FROM workspace_membership m JOIN \"user\" u ON u.id = m.user_id ORDER BY m.workspace_id, m.user_id) TO STDOUT WITH CSV HEADER" > "$snapshot_dir/workspace_membership.csv"
dc exec -T postgres psql -U root -d hephaestus -v ON_ERROR_STOP=1 -c "\copy (SELECT id, workspace_id, actor_id, event_type, occurred_at, xp FROM activity_event WHERE xp <> 0 ORDER BY occurred_at, id) TO STDOUT WITH CSV HEADER" > "$snapshot_dir/activity_event_xp.csv"
(cd "$snapshot_dir" && sha256sum ./*.csv > SHA256SUMS)
printf 'Snapshot directory: %s\n' "$snapshot_dir"
)
```

The three files hold member logins and per-member scores, which is personal data: move the directory off
the host with your backups, encrypted, keep it only as long as you need the history, and then delete it. The
application never reads it back.

**Delete the removed settings.** Remove `LEADERBOARD_NOTIFICATION_ENABLED`, `LEADERBOARD_SCHEDULE_DAY`
and `LEADERBOARD_SCHEDULE_TIME` from your `.env`, and any `hephaestus.leaderboard.*` override. They are
no longer read.

**Activity is for the workspace only.** A publicly viewable workspace does not show activity to people
without a role in it. Old `/w/{workspaceSlug}/user/{username}` links open that member on Workspace
activity, and `/w/{workspaceSlug}/user/{username}/practice-groups/{groupSlug}` opens the group on the
Practice profile. Remove clients of the leaderboard, league, profile and Slack-digest endpoints; the
new read endpoints are under `/workspaces/{workspaceSlug}/activity/`.
