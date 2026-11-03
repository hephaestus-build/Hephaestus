#### 🔴 Stop every role: the practice-review assignment's "Max concurrent reviews" is removed

The upgrade drops the column that stored **Max concurrent reviews**. A role that still runs the earlier version cannot read the new schema, so stop every role before the upgrade.

Reviews are now paced by capacity: a worker's review slots, the reviews quota of its sandbox namespace, and the fair grants of the model's call pool. To limit how much one provider serves, set a limit on its [call pool](https://docs.hephaestus.build/admin/runtime-roles#call-pools).
