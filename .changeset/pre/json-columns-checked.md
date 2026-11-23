---
"hephaestus": patch
---

Each JSON column now accepts only the JSON type that the server writes there. A value of another type, such as a JSON string where the server stores an object, is now refused when it is written. Before, it failed only when the server read it.

The upgrade checks the rows that are already stored. The server has always written these types. Thus, the upgrade usually needs no action. If a row was changed by hand, the upgrade stops before it checks that table's stored rows. It names each column with its number of such rows.

**Operators:** Only if the upgrade stops at a table's stored JSON values: correct them as the migration guide describes. Then start the upgrade again.
