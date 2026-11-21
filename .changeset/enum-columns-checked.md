---
"hephaestus": patch
---

The database now refuses a value that the server cannot read in each column that stores a fixed set of values, such as a status, a kind or a role. The upgrade checks the rows that are already stored. No release wrote such a value, so the upgrade needs no action. If a row was changed by hand, the upgrade stops and names the check that the row breaks, such as `ck_activity_event_event_type`. Correct or delete that row, then start the upgrade again.
