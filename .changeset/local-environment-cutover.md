---
"hephaestus": patch
---

New local databases get a random password and a Compose project name for their checkout. If your checkout already has database files, set its actual `POSTGRES_PASSWORD` and `COMPOSE_PROJECT_NAME` before you run local database commands. Commands no longer assume the password `root` or the shared project name `server`. To discard local data, set both values explicitly, then run `vp run dev:reset`. Kubernetes installations are not affected.
