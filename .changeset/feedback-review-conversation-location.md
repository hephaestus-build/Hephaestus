---
"hephaestus": minor
---

The feedback review shows every piece of feedback for a developer's conversation as **In conversation**. To locate it, the review no longer reads the developer's conversation, so the location label no longer says whether the feedback was raised in the app or in Slack, or whether it was raised yet. The delivery state stays. The counts of where feedback goes, and the **Where** filter, now have one conversation row instead of three.

API clients: the `location` filter of `GET /workspaces/{workspaceSlug}/practices/reviews/feedback`, `…/feedback/locations` and `…/feedback/reasons` accepts `CONVERSATION` in place of `APP`, `SLACK` and `NEXT_CONVERSATION`. The `location` of a review feedback response uses the same single value.
