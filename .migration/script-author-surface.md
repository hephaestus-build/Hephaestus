#### 🔴 Update custom practice script imports

Before upgrading, check custom scripts for these removed imports:

- `@hephaestus/practice-scripts/models`
- `threadRecordSchema` from `@hephaestus/practice-scripts/chat`
- `documentRecordSchema` from `@hephaestus/practice-scripts/docs`

Use `chat(ctx)` and `docs(ctx)` for domain records. Keep `meta` empty, prepare evidence with the public helpers, and call `ctx.review`. Set the review model and budget in the workspace. Validate each updated script before enabling it.

The four supported entries are `@hephaestus/practice-scripts`, `/scm`, `/chat`, and `/docs`. Private relative imports and removed entry points are rejected.
