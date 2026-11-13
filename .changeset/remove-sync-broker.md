---
"hephaestus": minor
---

Hephaestus no longer needs a NATS broker. Live sync hints travel between server replicas through PostgreSQL; the app still reads sync state through REST and polls if a hint is missed.

**Operators:** Remove the retired broker values and image entry from every values file before upgrading, as the migration note lists. Remove broker environment settings and unused credentials. The chart refuses retired keys and no longer creates broker objects. The PostgreSQL budget includes the sync notification listener during rolling updates.

With the existing template requests, removing the broker lets the small profile admit eleven automatic turns without backups and seven with backups, alongside five reviews. The requests remain unmeasured template values; qualification owns workload right-sizing.
