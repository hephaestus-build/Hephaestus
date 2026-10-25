#### 🔴 Deploy matching runtime roles for retained review evidence

Drain and stop the runtime roles before the database upgrade.
Use the [role drain procedure](https://docs.hephaestus.build/admin/runtime-roles#graceful-drain).
Make and verify a database backup first.
Keep the signed release lock and configuration with the backup.
Deploy matching server, worker and webhook images.

The additive migration stores bounded original evidence on new pull-request, merge-request and submitted-review events.
It also adds an unavailable-original decision reason.
An earlier server or worker cannot read that new reason.
Do not mix old and new runtime versions against this database.
Return to an earlier release only through the [compatible-backup restoration procedure](https://docs.hephaestus.build/admin/upgrade#rollback).

Events from earlier releases have no retained original.
Their event-driven reviews are withheld if the original is needed.
No migration reconstructs that original from current work.
Existing observations and feedback stay unchanged.
An explicit new request can review the current work under its normal checks.

The [personal data map](https://docs.hephaestus.build/admin/dsms/personal-data-map) owns retention and erasure of the new evidence.
