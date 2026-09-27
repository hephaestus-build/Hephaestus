#### 🔴 Leaderboard, leagues and XP retired; Activity replaces them

The leaderboard, leagues and league points, experience points and levels, the league reset, and the
weekly Slack leaderboard digest are removed. So are the workspace switches **Leaderboard**, **XP and
level progression** and **Leagues**, the digest's day, time, channel and team settings, and the Slack
connection card's test message. Every workspace now has **Activity** (the workspace home) and
**Workspace activity**: counts and lists of pull or merge requests, reviews, issues and comments, with
members listed by name — see [Activity](https://docs.hephaestus.build/user/activity).

**League and XP history is no longer shown.** This release stops reading and writing
`workspace_membership.league_points`, `activity_event.xp` and the workspace's leaderboard and league
columns but leaves them in the database; a later release drops them, so back up before that release if
you want to keep them. The upgrade attributes each recorded merge to the pull request's author; a merge
whose author is unknown is no longer counted for anyone.

**Delete the removed settings.** Remove `LEADERBOARD_NOTIFICATION_ENABLED`, `LEADERBOARD_SCHEDULE_DAY`
and `LEADERBOARD_SCHEDULE_TIME` from your `.env`, and any `hephaestus.leaderboard.*` override. They are
no longer read.

**Activity is for the workspace only.** A publicly viewable workspace does not show activity to people
without a role in it. Old `/w/{workspaceSlug}/user/{username}` links open that member on Workspace
activity, and `/w/{workspaceSlug}/user/{username}/practice-groups/{groupSlug}` opens the group on the
Practice profile. Remove clients of the leaderboard, league, profile and Slack-digest endpoints; the
new read endpoints are under `/workspaces/{workspaceSlug}/activity/`.
