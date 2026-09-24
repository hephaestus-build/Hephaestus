#### 🔴 Check events and pipeline events

The schema migration applies automatically. What needs a hand is the event stream: a GitHub App
created from an earlier manifest does not subscribe to `check_suite` and `status`, and a GitLab group
webhook registered by an earlier release does not send pipeline events. Add the two event
subscriptions under the GitHub App's Permissions & events, and grant Checks and Commit statuses read
if the app predates them (each installation approves the increase). Enable Pipeline events on each
GitLab group hook under the group's Settings → Webhooks, or delete the hook so it is registered again
on the next sync. Until then the head's check state arrives only with the scheduled sync, which reads
it with the pull request; nothing else is affected.
