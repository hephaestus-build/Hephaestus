---
"hephaestus": minor
---

Practice reviews now report whether a practice was met, not met, not applicable, or undetermined. Practice authors define the review occasion and evidence directly, without a nested occasion list.

**Operators:** This pre-1.0 release removes the previous practice and observation API fields. Back up the database before upgrading, stop running reviews, and deploy the server, review runtime, webapp, and browser extension together. Update custom catalogue files and API clients to the new contract. Read the migration guide before upgrading; restoring the verified backup is the recovery path.
