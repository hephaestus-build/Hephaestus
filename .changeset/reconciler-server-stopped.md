---
"hephaestus": patch
---

The pull-based reconciler now checks `server.stopped` before a stopped major upgrade, and it starts the target with the server count from your site values. From a chart without `server.stopped`, such as 1.0.0-rc.8, it still accepts zero server replicas. The installed tooling runs each cutover. A cutover that tooling from 1.0.0-rc.8 or earlier runs still starts one server, so restore your server count after it, as phase 3 describes.
