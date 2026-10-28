#### 🔴 Prepare for the interfaces that 1.0 removes

This release deprecates settings and interfaces for removal in 1.0.
It does not remove them.
The release note **0.95.0 is the last 0.x release** lists each affected setting, value and endpoint.
Use that list to check your deployment and API callers before 1.0.
It includes `reasoningEffort` and `clearReasoningEffort` on both model-update requests.

Upgrade a Compose install to 0.95.0 before you move it to the chart at the same version.
Keep its PostgreSQL and JetStream backups, encryption keys, values and signed release lock.
Then follow [Upgrade from 0.x to 1.0](https://docs.hephaestus.build/admin/upgrade-to-1-0).
Do not start a 0.x runtime role on the 1.0 schema.

The release note also lists 21 public operations that 1.0 removes.
Update any automation that calls those exact methods and paths.
The immediate scan action and membership removal stay available.
A removed HTTP wrapper does not remove its underlying records.

The same release note lists all conversation renames for the 1.0 upgrade.
Update configuration, dashboard queries, spans and API callers with those exact replacements.
The list includes HTTP paths, operation IDs, schema names, DTO fields and enum values.
`FREE_HEPH` becomes `CAPACITY` only in 1.0. The field name stays `waitingFor`.
The old web-page redirect and Slack message identifiers remain as stated in that notice.
