---
"hephaestus": patch
---

Server logs no longer name people by their GitHub or GitLab login, username or commit email address. They name them by Hephaestus and provider ids instead. On an instance with bootstrap admins, the sign-in of a new account that is not on the list now logs only its provider subject; add `provider:subject` to make it an admin. The data retention guide recommends an age limit for your log store.
