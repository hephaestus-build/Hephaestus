---
"hephaestus": patch
---

A review no longer fails when this server briefly loses its database connection while the review hands in its results, for example when PostgreSQL restarts. The review asks again until the server answers or its time is up, and keeps its work, instead of ending with "The server answered the review's results with its own error."
