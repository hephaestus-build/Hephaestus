---
"hephaestus": minor
---

Old webapp addresses from before 1.0 no longer redirect, so replace any bookmarks with links copied from the current pages.

- `/landing` no longer works. Use `/`.
- `/w/<workspace>/user/<username>` no longer works. To see a person, open them from **Workspace activity**.
- `/w/<workspace>/user/<username>/practice-groups/<group>` no longer works. To see a practice group, open it on your **Practice profile**.
- Old review addresses under **Practice reviews**, `…/admin/practices/reviews/review/<review>` and its `?focus=` steps, no longer work. Open the review from the **Reviews** tab.
- `range=1y` on the **Practice reviews** overview now opens the last 30 days. Use `range=all` for all time.

If you chose a theme before the theme followed you across workspace addresses, choose it once more. Hephaestus no longer reads that earlier choice.

The webapp image no longer ships the development mock service worker.
