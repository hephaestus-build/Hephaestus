#### 🔴 Stop every role before the rc.1 admission cutover

Include this change in the stopped rc.1 upgrade. Stop every runtime role before you run the migration. The migration removes claim-only waiters and closes unused provider grants. It preserves actual calls, their rate debits, and scheduling history. Then start every role from the new release. Older workers must stay stopped because they can recreate the retired reservations.

Keep the verified pre-upgrade backup until the cutover is complete. To return to the older release, stop every role and restore that backup before you start it. Do not roll the data migration back in place.
