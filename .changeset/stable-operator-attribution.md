---
"hephaestus": minor
---

Instance settings and connection lifecycle history now record the acting administrator by stable account reference instead of a display login.

**Operators:** The upgrade drops the old administrator login attribution for silent-mode and instance model settings. It retains the settings and their change times. Old logins are not matched to accounts. It also clears `actor_ref` and free-text `detail` from existing `ADMIN` and `USER` connection audit rows. Event types, state changes and times remain. New connection history uses typed account references; provider and system event references remain. Take a verified backup before upgrading if you need the historical attribution for your retention policy.
