---
"hephaestus": patch
---

The database checks on review, feedback, practice, item, agent and workspace records now run after each write, so a checked update no longer holds a row lock that delays other writes to related records.
