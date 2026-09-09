---
"hephaestus": patch
---

Signing out works again. The browser could not find the CSRF token the server requires, so the server rejected the request and the app reported that it could not confirm the sign-out. Other actions that change something were rejected the same way. Instances that never set `XSRF_COOKIE_NAME`, which is every instance following the shipped configuration, were affected.
