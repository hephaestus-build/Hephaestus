#### 🔴 Stop every role before recording failed and handed-off run outcomes

Runs now record a failed outcome, and a review keeps the run that measured it after its composition starts. The migration corrects old runs that recorded a completed outcome for failed, timed-out or cancelled work. It keeps their identities, epochs, times and usage.

**Operators:** Use [Stopped schema upgrades](https://docs.hephaestus.build/admin/upgrade#stopped-schema-upgrades). Drain work and stop every runtime role before the migration, then start only the upgraded roles. The previous server cannot read a run with a failed or handed-off outcome, so do not run old and new servers together during this change. If the migration reports a review or turn that still disagrees with its completed run, it stops and changes nothing.

To roll back, stop every role and restore the verified backup from before the migration before starting the previous version.
