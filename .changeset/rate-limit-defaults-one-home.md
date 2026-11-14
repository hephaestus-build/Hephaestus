---
"hephaestus": patch
---

The rate limits on sign-in, session refresh, account deletion, data exports, conversation turns, review requests, sync triggers, public Activity and browser reports now have one default each, in the shipped application configuration. If a limit is missing from the configuration, or its environment variable is set to an empty value, the server refuses to start and names every missing setting. Before, a missing limit fell back to 20 requests a minute without a warning, which for account deletion is far looser than the intended 3 an hour. The environment variables that set the limits are unchanged.
