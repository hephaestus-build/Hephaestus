---
"hephaestus": minor
---

Workspace activity lists everyone with work in the range in one table that you can sort by each count. It sorts by Contributions first: pull requests opened, pull requests reviewed, and issues opened. A sorted table shows each person's position. Choose 30 days, 90 days, 12 months, all time, or a custom range, then pick a team and repositories. The address keeps your view, so you can share it. The page says how far back the history is complete. A **New** badge marks a first contribution in the range. Each figure uses GitHub's or GitLab's own icon and colour. Bot accounts appear apart under Automation, and a workspace admin can treat a machine user account as automation or count it as a person again. Open a person to see their weekly bars, their counts in each repository, and their work. Your own Activity page gets the same ranges and weekly bars, in place of the daily and monthly bars. On GitLab, drawers, popovers and tooltips now use GitLab's colours too.

**Operators:** Custom activity API clients read the new `kind` field (`PERSON`, `BOT` or `AUTOMATION`) instead of `automation`. The bundled webapp needs no action.
