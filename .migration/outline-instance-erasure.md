#### 🔴 Stop every role before the Outline erasure upgrade

- **When:** in the window
- **Action:** Follow the [stopped upgrade](https://docs.hephaestus.build/admin/upgrade#stopped-schema-upgrades). Stop every role, run the migration, and start all roles on the new release.

An older server can write Outline rows without the origin key that erasure uses.
It can also run an erasure with the earlier selection rules.
The migration fills the origin keys of existing Outline rows and rejects writes that omit the key.
