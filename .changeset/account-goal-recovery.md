---
"@hephaestus/server": patch
---

Use version 5 of the erasure recovery manifest. It lists forgotten goal IDs so a restore can clear those goals and withdraw the related ready exports before the server opens access.
