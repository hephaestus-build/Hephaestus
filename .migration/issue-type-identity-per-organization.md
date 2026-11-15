#### 🔴 Stop every role before issue types become unique per organization

An issue type now belongs to one organization. The migration gives each issue type its own key and keeps the provider's id as a separate column. A type that issues on another provider instance used is copied to that instance's organization, and those issues move to the copy. Issues of a GitLab subgroup keep their root group's type.

**Operators:** Use [Stopped schema upgrades](https://docs.hephaestus.build/admin/upgrade#stopped-schema-upgrades). Drain work and stop every runtime role before the migration, then start only the upgraded roles. The previous server cannot read the new issue type keys, so do not run old and new servers together during this change.

To roll back, stop every role and restore the verified backup from before the migration before starting the previous version.
