---
"hephaestus": minor
---

A new installation now starts from one database baseline, the schema of 1.0.0-rc.1, instead of replaying the 0.x migrations.

**Operators:** A database on 1.0.0-rc.1 moves to the baseline by itself in the next migration, and only when its schema equals that release. If it differs, or if a later build ran on it, the migration stops, changes nothing and lists what it found. A database from before 1.0.0-rc.1 is refused: install 1.0.0-rc.1 and complete its upgrade first.
