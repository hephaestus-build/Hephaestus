#### 🔴 Stop every role before the source contract 3.0.0 upgrade

Before reviews resume after the upgrade, update custom scripts and criteria that read these fields from `metadata.json`:

- Read `head_checks`, `head_check_runs`, and `merge_state_status` from `checks.json`, under `scm.pull-request.checks`.
- Read `review_decision` from `review_threads.json`, under `scm.review-threads`.

The upgrade migrates current workspace practices, catalog overrides, and their comparison bases from contract 2.0.0 to 3.0.0. It preserves custom criteria and scripts. Practices that read pull request details gain optional reads for the two separate sources. Historical practice versions stay unchanged. The runtime accepts only contract 3.0.0 for a new capture.


Use the [stopped 0.95.0 upgrade procedure](https://docs.hephaestus.build/admin/upgrade). To reverse this migration, restore the database backup taken before the upgrade.

Queued reviews and unfinished scans without results receive new pins for their original definitions. Interrupted jobs recapture evidence and do not spend a retry. A review or scan with recorded results, reused results, or staged output stops with `CONTRACT_CHANGED`. Its results and historical version references stay unchanged. After the upgrade, request a new review or scan for the remaining work. This is an upgrade interruption, not feedback about the work.

The `mergeLead(record, merge, label)` script API stays unchanged. Adopted catalog scripts can keep their calls.
