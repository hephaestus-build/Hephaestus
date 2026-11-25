---
"hephaestus": patch
---

The database removes temporary table creation from the Hephaestus roles. Invariant checks now name their table schemas so that a session cannot redirect them to temporary tables.
