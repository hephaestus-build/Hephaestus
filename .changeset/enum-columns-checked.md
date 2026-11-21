---
"hephaestus": patch
---

Some columns store a fixed set of values, such as a status, a kind or a role. The database now refuses a value in them that the server cannot read. The upgrade checks the rows that are already stored.

Earlier upgrades corrected each such value that a release stored. Thus, the upgrade usually needs no action. If a row was changed by hand, the upgrade stops before it checks that table. It names each column with its number of such rows.

**Operators:** Only if the upgrade stops at a table's stored rows: correct them as the migration guide describes. Then start the upgrade again.
