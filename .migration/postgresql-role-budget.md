#### 🔴 Recalculate custom PostgreSQL connection budgets

The small profile now has an explicit PostgreSQL connection ceiling and separate role pools.
Apply the change through the existing [stopped database-maintenance procedure](https://docs.hephaestus.build/admin/kubernetes#database-maintenance).
Do not lower the database ceiling while older, larger pools are still running.
For custom worker replica counts or pool sizes, set `postgresql.parameters.max_connections` and each role's `databasePool` values together before upgrading.
The chart rejects a ceiling that cannot hold the configured worker overlap and maintenance reserves.
See [Database connections](https://docs.hephaestus.build/admin/sizing#database-connections) for the calculation.

Check node capacity against the full `postgresql.resources` and `postgresql.backup.resources` memory requests before upgrading.
Count the resident backup sidecar and each backup or stanza Job separately.
Existing memory limits remain in place until representative load qualifies lower ceilings.
