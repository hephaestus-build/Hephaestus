---
"hephaestus": minor
---

Practice observations now distinguish whether a practice was assessed from whether its target behaviour was present and whether that was good or bad. Not applicable and undetermined observations no longer masquerade as presence values. Invalid combinations are rejected instead of silently rewritten, and severity belongs only to bad assessments.

**Operators:** This changes the observation API and runtime output contract. Upgrade the server, sandbox runtime and webapp together; update custom consumers to read `assessmentStatus` and nullable `presence`, `assessment` and `severity`. Back up the database before upgrading. The migration preserves existing judgments and stops rather than inventing a severity for an inconsistent historical bad observation. See the migration guide before upgrading.
