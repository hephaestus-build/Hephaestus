---
"hephaestus": minor
---

New practice identifiers cannot use names reserved for practice API routes. The practice editor and both create APIs use the same validation rule. Group identifiers keep their format, and existing identifiers are not renamed.

**Operators:** Check existing workspace and catalog practices for reserved identifiers before upgrading. Resolve any collision before the upgrade. The migration guide lists the preflight query. Practice identifiers remain immutable.
