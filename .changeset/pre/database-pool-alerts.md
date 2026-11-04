---
"hephaestus": minor
---

Two new alerts show when the database connection pool makes requests wait. `HephaestusDatabaseConnectionTimeouts` fires when a role could not get a database connection in time, so a request or a job failed. `HephaestusDatabasePoolSlow` fires when more than 5 % of a role's connections took longer than 50 ms to get for 30 minutes, which misses the sizing target. Load the rule file of the release to get them.
