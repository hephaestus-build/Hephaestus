#### 🔴 Stop every role before the source contract 3.0.0 upgrade

Before reviews resume after the upgrade, update custom scripts and criteria that read these fields from `metadata.json`:

- Read `head_checks`, `head_check_runs`, and `merge_state_status` from `checks.json`, under `scm.pull-request.checks`.
- Read `review_decision` from `review_threads.json`, under `scm.review-threads`.

The upgrade migrates current workspace practices, catalog overrides, and their comparison bases from contract 2.0.0 to 3.0.0. It preserves custom criteria and scripts. Practices that read pull request details gain optional reads for the two separate sources. Historical practice versions stay unchanged. The runtime accepts only contract 3.0.0 for a new capture.

If a custom script calls `mergeLead`, pass the merge facts and label: `mergeLead(merge, label)`.

Use the stopped upgrade procedure for 1.0. To reverse this migration, restore the database backup taken before the upgrade.
