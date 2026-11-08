---
"hephaestus": minor
---

Issue discussions with an unknown comment count no longer count as complete evidence. Existing zero issue comment counts show as unknown until the next backfill. Positive counts and pull request counts keep their values. Use **Run backfill** for each affected workspace to recover the counts, then request a new review for work whose evidence was incomplete.

**Operators:** Stop every runtime role before this upgrade and take a verified database backup. Run the migration, then start only the upgraded roles. The previous server cannot read unknown comment counts. Restore that backup before rolling back the server.
