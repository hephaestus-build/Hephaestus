---
"hephaestus": patch
---

A host that follows the default branch no longer restarts its database on every apply. The
PostgreSQL image is rebuilt for every commit, so each apply used to bring the database container
back up under a new image, dropping every connection for minutes, failing the practice reviews in
flight and answering 503 meanwhile. The host now carries the PostgreSQL image it runs across
applies. It takes the commit's image when that commit changes what the image is built from, and
otherwise on the first apply of a new day, so the security updates the rebuild carries still reach
the host within a day. Promote with **refresh-database-image** to take a commit's image at once. A
release still applies exactly the images it was signed with.
