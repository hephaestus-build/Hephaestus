---
"hephaestus": patch
---

The workspace isolation guard is stricter. A database read or write by a single key now passes without a workspace check only when that key is the row's own identity or the key of a list the row belongs to. For reads without a mapped key, the guard checks every table of a comma-separated join.
