#### 🔴 Administrator attribution uses account references

The upgrade removes the historical display-login attribution from silent-mode and instance model settings. The settings, change times and other operational facts are retained. The old values are not matched to names, logins or email addresses. New changes use a stable account reference, which is detached when that account is erased.

Existing `ADMIN` and `USER` connection audit rows lose their untyped `actor_ref` and free-text `detail`. Event types, state changes and times remain. New connection history uses typed account references. Provider and system event references are unchanged.

Before upgrading, take and verify a backup if your retention policy requires the old attribution. This removal cannot be reversed from the upgraded database; recovery requires the verified pre-upgrade backup. Do not backfill attribution by matching display logins to accounts.

The migration also clears membership-history subject references that have no exact contributor ID.
It retains the recorded role changes and their times. No historical name, login or email is used to
recover attribution.

Pending integration authorizations must be started again after the upgrade. Older signed OAuth states are rejected rather than interpreting their historical display-login attribution as an account reference.

New pending integration authorizations are tied to the initiating account and removed by person erasure. Existing nonce rows remain without account attribution; no old identity is inferred. Erasure revokes sign-in sessions as soon as the job starts, including when a later store step needs a retry.


Heph's hidden conversation memory resets once on upgrade. Old runtime journals have no complete
exact-person provenance, so the migration clears them without matching text, names, logins or emails.
Every visible message, conversation title and time stays. Recovery of the cleared hidden journals
requires a verified pre-upgrade backup. Users may need to repeat earlier context to Heph.

Existing source connections are registered for exact person requests, including Slack and Outline
sources with no account login. This creates only provider reference data and carries native processing
controls across equivalent origins. It does not change visible content or infer identity ownership.
If an Outline connection has mirrored documents but no exact provider instance, the upgrade stops and
names that connection ID. Restore a verified state with its exact source binding before upgrading;
do not recover a binding from names, logins, email addresses or document content.
