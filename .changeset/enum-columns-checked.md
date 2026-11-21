---
"hephaestus": patch
---

Some columns store a fixed set of values, such as a status, a kind or a role. The database now refuses a value in them that the server cannot read. The upgrade checks the rows that are already stored.

Earlier upgrades corrected each such value that a release stored. Thus, the upgrade needs no action. If a row was changed by hand, the upgrade stops and names the check that the row breaks, such as `ck_activity_event_event_type`. Correct or delete that row, then start the upgrade again.
