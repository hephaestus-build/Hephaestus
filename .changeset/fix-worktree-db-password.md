---
"hephaestus": patch
---

A local development checkout now creates its PostgreSQL database with a random password instead of the fixed password `root`. The first `vp run dev` writes the password to `server/.env`. A local database that already exists keeps working with its old password until you run `vp run dev:reset`. Kubernetes installations are not affected: the chart always reads the database password from your Secret.
