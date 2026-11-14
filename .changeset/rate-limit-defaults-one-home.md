---
"hephaestus": patch
---

Each sign-in, session and request rate limit now has one default, in the shipped application configuration. If a limit is missing from the configuration, the server refuses to start and names the missing setting. Before, a missing limit fell back to 20 requests a minute, which for account deletion is far looser than the intended 3 an hour. The environment variables that set the limits are unchanged.
