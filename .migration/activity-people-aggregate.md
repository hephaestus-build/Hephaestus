#### 🔴 Activity API cutover

Custom clients must replace `GET /workspaces/{slug}/activity/summary` and `GET /workspaces/{slug}/activity/members` with `GET /workspaces/{slug}/activity/people`.
The response separates people and automation and counts each reviewed pull request once.
Use `range=30d`, `90d`, `1y`, or `all`, or provide `from` and `to` for a custom range.
Team filters use the `teams[].key` value. Repository filters use `repositories[].key` and can repeat `repo`.
Ranges span at most 731 days. The people response supplies `maxRangeDays` and `historyStart`.
All time is rejected when the history exceeds this cap. Use bounded custom windows for earlier history.
The activity work endpoint also uses range presets and readable `team` and `repo` keys instead of `teamId`.
Use `GET /workspaces/{slug}/activity/people/{userId}` for a contributor's breakdown.
Use its `/work` subresource with `nextCursor` for subsequent pages.
The bundled webapp needs no operator action.
The database migration applies automatically and preserves the activity ledger.
