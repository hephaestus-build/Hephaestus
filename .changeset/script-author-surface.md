---
"hephaestus": minor
---

Practice scripts now import from the core, SCM, chat, and docs entries only. The direct-model entry `@hephaestus/practice-scripts/models` is removed. Chat and document record schemas are private; use the `chat` and `docs` helpers to read these records.

**Operators:** Before upgrading, update custom scripts that import model helpers or record schemas. Keep script `meta` empty, prepare evidence, and call `ctx.review`. Configure the review model and budget in the workspace. Validate the updated scripts before enabling them.
