#### 🔴 Administrator attribution uses account references

The upgrade removes the historical display-login attribution from silent-mode and instance model settings. The settings, change times and other operational facts are retained. The old values are not matched to names, logins or email addresses. New changes use a stable account reference, which is detached when that account is erased.

Existing `ADMIN` and `USER` connection audit rows lose their untyped `actor_ref` and free-text `detail`. Event types, state changes and times remain. New connection history uses typed account references. Provider and system event references are unchanged.

Before upgrading, take and verify a backup if your retention policy requires the old attribution. This removal cannot be reversed from the upgraded database; recovery requires the verified pre-upgrade backup. Do not backfill attribution by matching display logins to accounts.
