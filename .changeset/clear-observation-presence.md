---
"hephaestus": minor
---

Practice observations now distinguish whether a practice was assessed from whether the specified behavior was present and whether that behavior is desirable or undesirable in context. Not applicable and undetermined observations no longer masquerade as presence values. Invalid combinations are rejected instead of silently rewritten, and positive and negative outcomes are derived from presence and assessment. Severity belongs only to negative outcomes.

**Operators:** This changes the observation API and runtime output contract. Upgrade the server, sandbox runtime and webapp together; update custom consumers to read `assessmentStatus` and nullable `presence`, `assessment` and `severity`, plus the read-only `outcome`. Standing observations expose their descriptive `kind` separately. Back up the database before upgrading. The migration preserves existing outcomes by translating historical absence assessments and stops rather than inventing a severity for an inconsistent historical bad observation. See the migration guide before upgrading.
