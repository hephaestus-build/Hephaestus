---
"hephaestus": minor
---

Every workspace now opens on **Activity**: what is waiting on your review, your open pull or merge requests (with drafts, requested changes, approvals and failing checks called out), the issues assigned to you, a summary of what you did over the last 7 days, 30 days, 90 days or 12 months, and a timeline that opens each piece of work on GitHub or GitLab. **Workspace activity** replaces the leaderboard: everyone's activity, or one team's, with members listed by name and each member one click away. Nothing is scored or ranked anymore — the leaderboard, leagues, league points, XP, levels and the weekly Slack leaderboard digest are gone, and activity is visible only to people with a role in the workspace, never through a public workspace.

**Operators:** league, XP and leaderboard history is no longer shown but stays in the database until a later release removes it, so back up before that release if you want to keep it. Delete `LEADERBOARD_NOTIFICATION_ENABLED`, `LEADERBOARD_SCHEDULE_DAY` and `LEADERBOARD_SCHEDULE_TIME` from your environment.
