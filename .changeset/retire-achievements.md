---
"hephaestus": minor
---

Retires achievements, including badges, skill trees, unlock notifications, and achievement administration. Activity history, practice feedback, leaderboards, leagues, and XP progression remain available.

**Operators:** Remove links and integrations that use achievement pages or API endpoints, and stop sending `achievementsEnabled` in workspace feature updates. This upgrade permanently deletes stored achievement progress and the old workspace flag. Back up the database and stop all application runtime roles before upgrading; older versions must not run against the upgraded schema.
