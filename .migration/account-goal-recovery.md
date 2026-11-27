#### 🔴 Convert erasure recovery manifests to version 5

- **When:** before the window
- **Action:** Convert each retained version 4 manifest as described in [Reapply erasures after a restore](https://docs.hephaestus.build/admin/erasure-recovery).

Copy the retained manifest and keep its restore point and existing erasure entries. Set `version` to `5`.
Check the retained privacy journal for each `RECORD_DELETED` fact after the restore point.
Copy the goal UUID from `recordId` into the new `recordDeletions` list.
Use an empty list only after you verify that there are no such facts.
If there are later erasures, set `noLaterErasures` to `false`.

The upgraded server stops recovery if the manifest uses version 4 or has no `recordDeletions` list.
Keep the original manifest for recovery on the previous server.
