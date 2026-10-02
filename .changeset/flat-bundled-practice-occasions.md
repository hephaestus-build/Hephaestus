---
"hephaestus": minor
---

Practice reviews now report whether a practice was met, not met, not applicable, or undetermined. Practice authors define the review occasion and evidence directly, without a nested occasion list. Automatic review conditions use each work type’s recorded state, rather than a shared draft flag. Changing when a practice is reviewed does not invalidate assessments of its unchanged standard.

A met or not-met result recorded under an earlier assessment scheme, or with no scheme recorded, judged a single behavior rather than the whole practice: your review history still lists it as recorded, but a review's summary counts it as undetermined. A catalog update for a practice whose adopted version cannot be proved no longer preselects the offered version: choose current or offered for each changed field. A workspace copy made by an earlier release can compare updates against a recorded version matched by its saved source fingerprint; because that version may not show the original guidance or delivery, you also choose each changed field there.

**Operators:** This pre-1.0 release removes the previous practice and observation API fields. Back up the database before upgrading, stop running reviews, and deploy the server, review runtime, webapp, and browser extension together. If observations serve a research or audit purpose, export the original observation fields first. The upgrade stops without changing anything if a stored practice definition carries a field the new contract does not read. Keep automated reviews paused until every active practice's criteria describe its positive standard. Update custom catalogue files and API clients to the new contract. Read the migration guide before upgrading; restoring the verified backup is the recovery path.
