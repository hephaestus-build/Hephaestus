---
"hephaestus": minor
---

Instance settings and connection lifecycle history now record the acting administrator by stable account reference instead of a display login.

**Operators:** The upgrade drops the old administrator login attribution for silent-mode and instance model settings. It retains the settings and their change times. Old logins are not matched to accounts. It also clears `actor_ref` and free-text `detail` from existing `ADMIN` and `USER` connection audit rows. Event types, state changes and times remain. New connection history uses typed account references; provider and system event references remain. Take a verified backup before upgrading if you need the historical attribution for your retention policy.

Membership-history subject references that have no exact contributor ID are also cleared. Role
changes and their times are kept. Person erasure preserves another administrator's attribution on
shared history rows and removes only the erased person's actor or impersonator reference.

Pending integration authorizations must be started again after the upgrade. Older signed OAuth states are rejected rather than interpreting their historical display-login attribution as an account reference.

New pending integration authorizations are tied to the initiating account and removed by person erasure. Existing nonce rows remain without account attribution; no old identity is inferred. Erasure revokes sign-in sessions as soon as the job starts, including when a later store step needs a retry.
