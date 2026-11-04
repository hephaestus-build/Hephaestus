---
"hephaestus": patch
---

The upgrade removes 11 database indexes that repeated another index on the same table, so writes to those tables do slightly less work. Reads use the remaining index, and the upgrade needs no action.
