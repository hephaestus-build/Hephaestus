---
"hephaestus": patch
---

The database migration Job's log records now name the release that runs, in `service.version`, and the deployment environment, in `service.environment`, like the server, worker and webhook. Before, they named the development version `0.0.0-development` and the environment `local`. A log search by release now finds the migration of that release too.
