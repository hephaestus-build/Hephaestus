---
"hephaestus": patch
---

A host that follows the default branch no longer restarts its database on every apply. The
PostgreSQL image is rebuilt for every commit, so each apply used to bring the database container
back up under a new image, dropping every connection for minutes, failing the practice reviews in
flight and answering 503 meanwhile. The host now keeps the PostgreSQL image it runs until a commit
changes what that image is built from; a release still applies exactly the images it was signed
with.
