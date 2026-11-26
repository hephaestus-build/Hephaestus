#### 🔴 Stop every role before reviews wait for a full model pool

- **When:** in the window
- **Action:** Stop all runtime roles and wait for all run and action leases to expire. See [Stopped schema upgrades](https://docs.hephaestus.build/admin/upgrade#stopped-schema-upgrades).

A review that its model pool cannot take at its start now waits with the new run hold `MODEL_POOL`. A server of the previous release cannot read a run with that hold, so the two releases must not run together.
